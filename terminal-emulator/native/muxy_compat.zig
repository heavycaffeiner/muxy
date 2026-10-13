const std = @import("std");
const Terminal = @import("Terminal.zig");
const Parser = @import("Parser.zig");
const Style = @import("style.zig").Style;
const dcs = @import("dcs.zig");
const apc = @import("apc.zig");

pub const Callback = *const fn (?*anyopaque, u32, [*]const u8, usize) callconv(.c) void;

/// Termux bounded control strings to 16 KiB; iTerm2 images may be larger.
const max_control_bytes = 16 * 1024;
const max_image_bytes = 200 * 1024 * 1024;
const max_clipboard_bytes = 100 * 1024 + 10;

pub const State = struct {
    callback: ?Callback = null,
    userdata: ?*anyopaque = null,
    osc: std.ArrayList(u8) = .empty,
    osc_active: bool = false,
    osc_end: bool = false,
    osc_len: usize = 0,
    osc_limit: usize = max_control_bytes,
    sixel: bool = false,
    sixel_end: bool = false,
    dcs_len: usize = 0,
    xtgettcap: bool = false,
    chunk: [4096]u8 = undefined,
    chunk_len: usize = 0,
    rectangle: bool = false,
    titles: std.ArrayList([]u8) = .empty,
    current_byte: u8 = 0,
    abandon_dcs: bool = false,

    pub fn deinit(self: *State, alloc: std.mem.Allocator) void {
        self.osc.deinit(alloc);
        for (self.titles.items) |title| alloc.free(title);
        self.titles.deinit(alloc);
    }

    /// Soft terminal reset (DECSTR), matching Termux's reset().
    pub fn reset(self: *State, t: *Terminal) !void {
        const cb = self.callback;
        const user = self.userdata;
        self.deinit(t.gpa());
        self.* = .{ .callback = cb, .userdata = user };
        // The active screen survives a soft reset, so its modes do too.
        const legacy = t.modes.get(.alt_screen_legacy);
        const alternate = t.modes.get(.alt_screen);
        const save_clear = t.modes.get(.alt_screen_save_cursor_clear_enter);
        t.modes.reset();
        t.modes.set(.alt_screen_legacy, legacy);
        t.modes.set(.alt_screen, alternate);
        t.modes.set(.alt_screen_save_cursor_clear_enter, save_clear);
        t.setCursorStyle(.default);
        t.flags.modify_other_keys_2 = false;
        t.tabReset();
        t.scrolling_region = .{ .top = 0, .bottom = t.rows - 1, .left = 0, .right = t.cols - 1 };
        for ([_]?*@import("Screen.zig"){ t.screens.get(.primary), t.screens.get(.alternate) }) |optional| {
            const screen = optional orelse continue;
            screen.cursor.style = .{};
            try screen.manualStyleUpdate();
            screen.cursor.pending_wrap = false;
            screen.cursor.protected = false;
            screen.saved_cursor = null;
            screen.charset = .{};
            screen.kitty_keyboard = .{};
        }
        t.colors.palette.resetAll();
        t.colors.foreground.reset();
        t.colors.background.reset();
        t.colors.cursor.reset();
        t.previous_char = null;
        self.emit(10, "");
    }

    pub fn emit(self: *State, kind: u32, data: []const u8) void {
        if (self.callback) |callback| callback(self.userdata, kind, data.ptr, data.len);
    }

    fn reply(self: *State, data: []const u8) void {
        self.emit(8, data);
    }

    fn flush(self: *State) void {
        if (self.chunk_len != 0) self.emit(2, self.chunk[0..self.chunk_len]);
        self.chunk_len = 0;
    }

    fn abortStrings(self: *State, t: *Terminal) void {
        if (self.sixel or self.sixel_end) self.emit(4, "");
        self.sixel = false;
        self.sixel_end = false;
        self.chunk_len = 0;
        self.osc.clearAndFree(t.gpa());
        self.osc_active = false;
        self.osc_end = false;
        self.osc_len = 0;
        self.dcs_len = 0;
        self.xtgettcap = false;
    }

    /// Sees every byte before the parser. Returns true when the byte is
    /// consumed here and must not reach the parser.
    pub fn byte(self: *State, t: *Terminal, dcs_handler: *dcs.Handler, apc_handler: *apc.Handler, parser: *Parser, c: u8) !bool {
        self.current_byte = c;
        if (c == 0x18 or c == 0x1a) {
            const in_string = parser.state == .osc_string or parser.state == .dcs_passthrough or
                parser.state == .dcs_ignore or parser.state == .sos_pm_apc_string;
            self.abortStrings(t);
            if (parser.state != .ground and !in_string) {
                // Termux printed a DEL glyph for an aborted escape sequence.
                parser.state = .ground;
                try t.print(0x7f);
                return true;
            }
            return false;
        }
        if (self.osc_end) {
            self.osc_end = false;
            if (c == '\\') self.oscComplete(t);
            self.osc.clearAndFree(t.gpa());
        }
        switch (parser.state) {
            // Termux read a leading ':' as an empty first parameter.
            .csi_entry => if (c == ':') {
                parser.params[0] = 0;
                parser.params_sep.set(0);
                parser.params_idx = 1;
                parser.param_acc = 0;
                parser.param_acc_idx = 0;
                parser.state = .csi_param;
                return true;
            },
            .osc_string => try self.oscByte(t, parser, c),
            .dcs_passthrough, .dcs_param, .dcs_intermediate, .dcs_entry => {
                if (c == 0x1b or c == 0x9c or c == 7) return false;
                self.dcs_len += 1;
                if (!self.sixel and self.dcs_len > max_control_bytes) {
                    // Overlong DCS: end it and print what follows, as Termux did.
                    dcs_handler.deinit();
                    self.dcs_len = 0;
                    self.xtgettcap = false;
                    parser.state = .ground;
                    return false;
                }
            },
            .sos_pm_apc_string => _ = apc_handler,
            else => {},
        }
        return false;
    }

    fn oscByte(self: *State, t: *Terminal, parser: *Parser, c: u8) !void {
        if (!self.osc_active) {
            self.osc_active = true;
            self.osc_len = 0;
            self.osc_limit = max_control_bytes;
            self.osc.clearRetainingCapacity();
        }
        if (c == 7 or c == 0x9c or c == 0x1b) {
            self.osc_active = false;
            if (c == 0x1b) {
                self.osc_end = true;
            } else {
                self.oscComplete(t);
                self.osc.clearAndFree(t.gpa());
            }
            return;
        }
        self.osc_len += 1;
        if (self.osc_len == 5 or self.osc_len == 3) {
            if (std.mem.startsWith(u8, self.osc.items, "1337")) self.osc_limit = max_image_bytes
            else if (std.mem.startsWith(u8, self.osc.items, "52;")) self.osc_limit = max_clipboard_bytes;
        }
        if (self.osc_len > self.osc_limit) {
            // Overlong OSC: drop it and return to ground, as Termux did.
            self.osc.clearAndFree(t.gpa());
            self.osc_active = false;
            parser.osc_parser.reset();
            parser.state = .ground;
            return;
        }
        if (self.osc.items.len < 5 or std.mem.startsWith(u8, self.osc.items, "1337;") or
            std.mem.startsWith(u8, self.osc.items, "1;"))
        {
            try self.osc.append(t.gpa(), c);
        }
    }

    fn oscComplete(self: *State, t: *Terminal) void {
        if (std.mem.startsWith(u8, self.osc.items, "1337;")) self.emit(5, self.osc.items);
        if (std.mem.startsWith(u8, self.osc.items, "1;")) {
            t.setTitle(self.osc.items[2..]) catch return;
            self.emit(6, self.osc.items[2..]);
        }
        self.osc_len = 0;
    }

    pub fn raw(self: *State, t: *Terminal, dcs_handler: *dcs.Handler, action: Parser.Action) !bool {
        switch (action) {
            .execute => |c| switch (c) {
                0x08 => {
                    t.backspace();
                    return true;
                },
                else => {},
            },
            .dcs_hook => |hook| {
                self.dcs_len = 0;
                if (hook.final == 'q' and hook.intermediates.len == 0) {
                    self.sixel = true;
                    self.chunk_len = 0;
                    self.emit(1, "");
                    return true;
                }
                if (hook.final == 'q' and hook.intermediates.len == 1 and hook.intermediates[0] == '+') {
                    self.xtgettcap = true;
                    self.osc.clearRetainingCapacity();
                    return true;
                }
                if (hook.final == 'q' and hook.intermediates.len == 1 and hook.intermediates[0] == '$') {
                    self.osc.clearRetainingCapacity();
                    self.abandon_dcs = true;
                    return true;
                }
            },
            .dcs_put => |c| {
                if (self.sixel) {
                    self.chunk[self.chunk_len] = c;
                    self.chunk_len += 1;
                    if (self.chunk_len == self.chunk.len) self.flush();
                    return true;
                }
                if (self.xtgettcap or self.abandon_dcs) {
                    if (self.osc.items.len < max_control_bytes) try self.osc.append(t.gpa(), c);
                    return true;
                }
            },
            .dcs_unhook => {
                if (self.sixel) {
                    self.flush();
                    self.sixel = false;
                    self.sixel_end = self.current_byte == 0x1b;
                    if (!self.sixel_end) self.emit(if (self.current_byte == 0x9c) 3 else 4, "");
                    return true;
                }
                if (self.xtgettcap) {
                    self.xtgettcap = false;
                    try self.termcap(t);
                    self.osc.clearAndFree(t.gpa());
                    return true;
                }
                if (self.abandon_dcs) {
                    self.abandon_dcs = false;
                    // DECRQSS: Termux answers the conformance level, other
                    // settings go to Ghostty's encoder.
                    if (std.mem.eql(u8, self.osc.items, "\"p")) {
                        self.reply("\x1bP1$r64;1\"p\x1b\\");
                    } else {
                        var buf: [dcs.Command.DECRQSS.max_response_bytes]u8 = undefined;
                        const request: dcs.Command.DECRQSS = if (std.mem.eql(u8, self.osc.items, "m")) .sgr
                            else if (std.mem.eql(u8, self.osc.items, "r")) .decstbm
                            else if (std.mem.eql(u8, self.osc.items, "s")) .decslrm
                            else if (std.mem.eql(u8, self.osc.items, " q")) .decscusr
                            else .none;
                        self.reply(try request.encode(t, &buf));
                    }
                    self.osc.clearAndFree(t.gpa());
                    return true;
                }
                _ = dcs_handler;
            },
            .esc_dispatch => |esc| {
                if (self.sixel_end) {
                    self.sixel_end = false;
                    self.emit(if (esc.final == '\\') 3 else 4, "");
                }
                if (esc.intermediates.len == 0) switch (esc.final) {
                    '6', '9' => {
                        try horizontalIndex(t, esc.final == '9');
                        return true;
                    },
                    'E' => {
                        try t.index();
                        t.screens.active.cursorHorizontalAbsolute(if (t.modes.get(.origin)) t.scrolling_region.left else 0);
                        return true;
                    },
                    'c' => {
                        t.fullReset();
                        try self.reset(t);
                        t.screens.get(.primary).?.eraseHistory(null);
                        self.emit(9, "");
                        return true;
                    },
                    else => {},
                };
            },
            .csi_dispatch => |action_csi| return try self.csiAction(t, action_csi),
            else => {},
        }
        return false;
    }

    fn csiAction(self: *State, t: *Terminal, csi_action: Parser.Action.CSI) !bool {
        const screen = t.screens.active;
        const params = csi_action.params;
        const intermediates = csi_action.intermediates;
        if (std.mem.eql(u8, intermediates, "!") and csi_action.final == 'p') {
            try self.reset(t);
            return true;
        }
        if (std.mem.eql(u8, intermediates, "?") and csi_action.final == 'n' and arg(params, 0, 0) == 6) {
            var buf: [64]u8 = undefined;
            self.reply(try std.fmt.bufPrint(&buf, "\x1b[?{d};{d};1R", .{ screen.cursor.y + 1, screen.cursor.x + 1 }));
            return true;
        }
        if (std.mem.eql(u8, intermediates, "?") and (csi_action.final == 'h' or csi_action.final == 'l')) {
            for (params) |mode| {
                if (mode == 7) screen.cursor.pending_wrap = false;
                if (mode == 3) {
                    t.modes.set(.enable_left_and_right_margin, false);
                    t.scrolling_region = .{ .top = 0, .bottom = t.rows - 1, .left = 0, .right = t.cols - 1 };
                    t.eraseDisplay(.complete, false);
                    t.setCursorPos(1, 1);
                }
            }
        }
        if (csi_action.final == 'c' and (intermediates.len == 0 or std.mem.eql(u8, intermediates, ">"))) {
            if (arg(params, 0, 0) != 0) return true;
            self.reply(if (intermediates.len == 0) "\x1b[?64;1;2;4;6;9;15;18;21;22c" else "\x1b[>41;320;0c");
            return true;
        }
        if (intermediates.len == 0) switch (csi_action.final) {
            'J' => if (arg(params, 0, 0) == 3) {
                t.screens.get(.primary).?.eraseHistory(null);
                return true;
            },
            'A', 'B', 'C', 'D', 'a' => {
                const count = arg(params, 0, 1);
                if (csi_action.final == 'D' or csi_action.final == 'C' or csi_action.final == 'a') {
                    // Termux clamped horizontal moves to the margins even
                    // from outside them.
                    const x: usize = screen.cursor.x;
                    const target: usize = if (csi_action.final == 'D')
                        @max(t.scrolling_region.left, x -| count)
                    else
                        @min(t.scrolling_region.right, x +| count);
                    screen.cursorHorizontalAbsolute(@intCast(target));
                } else if (csi_action.final == 'A') {
                    screen.cursorUp(@intCast(@min(count, screen.cursor.y)));
                } else {
                    screen.cursorDown(@intCast(@min(count, t.rows - 1 - screen.cursor.y)));
                }
                screen.cursor.pending_wrap = false;
                return true;
            },
            'M', 'L' => {
                const x = screen.cursor.x;
                if (csi_action.final == 'M') t.deleteLines(arg(params, 0, 1)) else t.insertLines(arg(params, 0, 1));
                screen.cursorHorizontalAbsolute(x);
                screen.cursor.pending_wrap = false;
                return true;
            },
            else => {},
        };
        if (std.mem.eql(u8, intermediates, "*") and csi_action.final == 'x') {
            self.rectangle = arg(params, 0, 0) == 2;
            return true;
        }
        if (std.mem.eql(u8, intermediates, "$")) {
            switch (csi_action.final) {
                'x', 'z', '{', 'v', 'r', 't' => {
                    try rectangle(t, csi_action, self.rectangle);
                    return true;
                },
                else => {},
            }
        }
        if (intermediates.len == 0 and csi_action.final == 't') {
            switch (arg(params, 0, 0)) {
                11 => {
                    self.reply("\x1b[1t");
                    return true;
                },
                13 => {
                    self.reply("\x1b[3;0;0t");
                    return true;
                },
                19 => {
                    var buf: [64]u8 = undefined;
                    self.reply(try std.fmt.bufPrint(&buf, "\x1b[9;{d};{d}t", .{ t.rows, t.cols }));
                    return true;
                },
                20 => {
                    self.reply("\x1b]LIconLabel\x1b\\");
                    return true;
                },
                21 => {
                    self.reply("\x1b]l\x1b\\");
                    return true;
                },
                22 => {
                    if (self.titles.items.len >= 20) t.gpa().free(self.titles.orderedRemove(0));
                    const title = try t.gpa().dupe(u8, t.getTitle() orelse "");
                    errdefer t.gpa().free(title);
                    try self.titles.append(t.gpa(), title);
                    return true;
                },
                23 => {
                    if (self.titles.pop()) |title| {
                        defer t.gpa().free(title);
                        try t.setTitle(title);
                        self.emit(6, title);
                    }
                    return true;
                },
                else => {},
            }
        }
        return false;
    }

    /// XTGETTCAP with Termux's answers: its own names and its key encoding.
    fn termcap(self: *State, t: *Terminal) !void {
        var parts = std.mem.splitScalar(u8, self.osc.items, ';');
        while (parts.next()) |part| {
            if (part.len == 0) continue;
            if (part.len % 2 != 0 or part.len > 64) continue;
            var name_buf: [32]u8 = undefined;
            const name = std.fmt.hexToBytes(&name_buf, part) catch continue;
            const value = termcapValue(t, name);
            var out: [256]u8 = undefined;
            var writer: std.Io.Writer = .fixed(&out);
            if (value) |text| {
                try writer.print("\x1bP1+r{s}=", .{part});
                for (text) |b| try writer.print("{X:0>2}", .{b});
                try writer.writeAll("\x1b\\");
            } else {
                try writer.print("\x1bP0+r{s}\x1b\\", .{part});
            }
            self.reply(writer.buffered());
        }
    }
};

fn termcapValue(t: *Terminal, name: []const u8) ?[]const u8 {
    const app_cursor = t.modes.get(.cursor_keys);
    const app_keypad = t.modes.get(.keypad_keys);
    const Entry = struct { []const u8, []const u8, ?[]const u8 };
    // Name, normal value, and application-mode value where it differs.
    const table = [_]Entry{
        .{ "Co", "256", null },                .{ "colors", "256", null },
        .{ "TN", "xterm", null },              .{ "name", "xterm", null },
        .{ "%i", "\x1b[1;2C", null },          .{ "#2", "\x1b[1;2H", null },
        .{ "#4", "\x1b[1;2D", null },          .{ "*7", "\x1b[1;2F", null },
        .{ "k1", "\x1bOP", null },             .{ "k2", "\x1bOQ", null },
        .{ "k3", "\x1bOR", null },             .{ "k4", "\x1bOS", null },
        .{ "k5", "\x1b[15~", null },           .{ "k6", "\x1b[17~", null },
        .{ "k7", "\x1b[18~", null },           .{ "k8", "\x1b[19~", null },
        .{ "k9", "\x1b[20~", null },           .{ "k;", "\x1b[21~", null },
        .{ "F1", "\x1b[23~", null },           .{ "F2", "\x1b[24~", null },
        .{ "F3", "\x1b[1;2P", null },          .{ "F4", "\x1b[1;2Q", null },
        .{ "F5", "\x1b[1;2R", null },          .{ "F6", "\x1b[1;2S", null },
        .{ "F7", "\x1b[15;2~", null },         .{ "F8", "\x1b[17;2~", null },
        .{ "F9", "\x1b[18;2~", null },         .{ "FA", "\x1b[19;2~", null },
        .{ "FB", "\x1b[20;2~", null },         .{ "FC", "\x1b[21;2~", null },
        .{ "FD", "\x1b[23;2~", null },         .{ "FE", "\x1b[24;2~", null },
        .{ "kb", "\x7f", null },               .{ "kd", "\x1b[B", "\x1bOB" },
        .{ "kh", "\x1b[H", "\x1bOH" },         .{ "kl", "\x1b[D", "\x1bOD" },
        .{ "kr", "\x1b[C", "\x1bOC" },         .{ "K1", "\x1b[H", "\x1bOH" },
        .{ "K3", "\x1b[5~", null },            .{ "K4", "\x1b[F", "\x1bOF" },
        .{ "K5", "\x1b[6~", null },            .{ "ku", "\x1b[A", "\x1bOA" },
        .{ "kB", "\x1b[Z", null },             .{ "kD", "\x1b[3~", null },
        .{ "kDN", "\x1b[1;2B", null },         .{ "kF", "\x1b[1;2B", null },
        .{ "kI", "\x1b[2~", null },            .{ "kP", "\x1b[5~", null },
        .{ "kN", "\x1b[6~", null },            .{ "kR", "\x1b[1;2A", null },
        .{ "kUP", "\x1b[1;2A", null },         .{ "@7", "\x1b[F", "\x1bOF" },
    };
    for (table) |entry| {
        if (!std.mem.eql(u8, entry[0], name)) continue;
        return if (app_cursor and entry[2] != null) entry[2].? else entry[1];
    }
    if (std.mem.eql(u8, name, "@8")) return if (app_keypad) "\x1bOM" else "\n";
    return null;
}

fn horizontalIndex(t: *Terminal, forward: bool) !void {
    const screen = t.screens.active;
    screen.cursor.pending_wrap = false;
    const region = t.scrolling_region;
    if (forward and screen.cursor.x < region.right) {
        screen.cursorRight(1);
        return;
    }
    if (!forward and screen.cursor.x > region.left) {
        screen.cursorLeft(1);
        return;
    }
    const origin = t.modes.get(.origin);
    t.modes.set(.origin, false);
    defer t.modes.set(.origin, origin);
    if (region.right > region.left) {
        var params = [_]u16{ region.top + 1, region.left + (if (forward) @as(u16, 2) else 1), region.bottom + 1,
            region.right + (if (forward) @as(u16, 1) else 0), 1, region.top + 1,
            region.left + (if (forward) @as(u16, 1) else 2), 1 };
        try rectangle(t, .{ .intermediates = @constCast("$"), .params = &params, .params_sep = .initEmpty(), .final = 'v' }, true);
    }
    const x = if (forward) region.right else region.left;
    var params = [_]u16{ ' ', region.top + 1, x + 1, region.bottom + 1, x + 1 };
    try rectangle(t, .{ .intermediates = @constCast("$"), .params = &params, .params_sep = .initEmpty(), .final = 'x' }, true);
}

fn arg(params: []const u16, i: usize, default: usize) usize {
    return if (i >= params.len or params[i] == 0) default else params[i];
}

pub fn decodedStyle(encoded: u64) Style {
    var result: Style = .{};
    result.flags.bold = encoded & 1 != 0;
    result.flags.italic = encoded & 2 != 0;
    result.flags.underline = if (encoded & 4 != 0) .single else .none;
    result.flags.blink = encoded & 8 != 0;
    result.flags.inverse = encoded & 16 != 0;
    result.flags.invisible = encoded & 32 != 0;
    result.flags.strikethrough = encoded & 64 != 0;
    result.flags.faint = encoded & 256 != 0;
    const fg = (encoded >> 40) & 0xffffff;
    const bg = (encoded >> 16) & 0xffffff;
    result.fg_color = decodedColor(fg, encoded & 512 != 0);
    result.bg_color = decodedColor(bg, encoded & 1024 != 0);
    return result;
}

fn decodedColor(value: u64, rgb: bool) Style.Color {
    if (rgb) return .{ .rgb = .{ .r = @truncate(value >> 16), .g = @truncate(value >> 8), .b = @truncate(value) } };
    return if (value < 256) .{ .palette = @intCast(value) } else .none;
}

pub fn cell(t: *Terminal, x: usize, y: usize, cps: []const u21, style: Style, protected: bool, uri: ?[]const u8) !void {
    const screen = t.screens.active;
    const old = screen.cursor;
    const modes = t.modes;
    const previous = t.previous_char;
    const charset = screen.charset;
    const alloc = t.gpa();
    const old_uri = if (old.hyperlink) |link| try alloc.dupe(u8, link.uri) else null;
    defer if (old_uri) |value| alloc.free(value);
    const old_id = if (old.hyperlink) |link| switch (link.id) {
        .explicit => |id| try alloc.dupe(u8, id),
        .implicit => null,
    } else null;
    defer if (old_id) |value| alloc.free(value);
    defer {
        t.modes = modes;
        t.previous_char = previous;
        screen.charset = charset;
        screen.cursorAbsolute(old.x, old.y);
        screen.cursor.protected = old.protected;
        screen.cursor.pending_wrap = old.pending_wrap;
        screen.cursor.semantic_content_clear_eol = old.semantic_content_clear_eol;
    }
    errdefer {
        screen.endHyperlink();
        screen.cursor.style = old.style;
        screen.manualStyleUpdate() catch {};
    }
    t.modes.set(.insert, false);
    t.modes.set(.wraparound, false);
    screen.charset = .{};
    screen.endHyperlink();
    screen.cursorAbsolute(@intCast(x), @intCast(y));
    screen.cursor.pending_wrap = false;
    screen.cursor.semantic_content_clear_eol = false;
    screen.cursor.protected = protected;
    screen.cursor.style = style;
    try screen.manualStyleUpdate();
    if (uri) |value| try screen.startHyperlink(value, null);
    for (cps) |cp| try t.print(cp);
    screen.endHyperlink();
    screen.cursor.style = old.style;
    try screen.manualStyleUpdate();
    if (old_uri) |value| try screen.startHyperlink(value, old_id);
}

fn rectangle(t: *Terminal, csi: Parser.Action.CSI, rectangular: bool) !void {
    const offset: usize = if (csi.final == 'x') 1 else 0;
    const origin = t.modes.get(.origin);
    const top_margin: usize = if (origin) t.scrolling_region.top else 0;
    const left_margin: usize = if (origin) t.scrolling_region.left else 0;
    const bottom_margin: usize = if (origin) t.scrolling_region.bottom + 1 else t.rows;
    const right_margin: usize = if (origin) t.scrolling_region.right + 1 else t.cols;
    const top = @min(arg(csi.params, offset, 1) - 1 + top_margin, bottom_margin);
    const left = @min(arg(csi.params, offset + 1, 1) - 1 + left_margin, right_margin);
    const bottom = @min(arg(csi.params, offset + 2, t.rows) + top_margin, bottom_margin);
    const right = @min(arg(csi.params, offset + 3, t.cols) + left_margin, right_margin);
    if (top >= bottom or left >= right) return;
    const screen = t.screens.active;
    var snapshot = try screen.clone(screen.io, screen.alloc, .{ .active = .{ .x = 0, .y = 0 } }, .{ .active = .{ .x = t.cols - 1, .y = t.rows - 1 } });
    defer snapshot.deinit();
    const dy = @min(arg(csi.params, 5, 1) - 1 + top_margin, @as(usize, t.rows));
    const dx = @min(arg(csi.params, 6, 1) - 1 + left_margin, @as(usize, t.cols));
    if (csi.final == 'v' and (dy == t.rows or dx == t.cols)) return;
    var y = top;
    while (y < bottom) : (y += 1) {
        var x = if (csi.final == 'r' or csi.final == 't') (if (rectangular or y == top) left else left_margin) else left;
        const end = if ((csi.final == 'r' or csi.final == 't') and !rectangular and y + 1 != bottom) right_margin else right;
        while (x < end) {
            const pin = snapshot.pages.pin(.{ .active = .{ .x = @intCast(x), .y = @intCast(y) } }).?;
            const rac = pin.rowAndCell();
            const page = pin.node.page();
            var style: Style = if (rac.cell.style_id == 0) .{} else page.styles.get(page.memory, rac.cell.style_id).*;
            if (rac.cell.content_tag == .bg_color_palette) style.bg_color = .{ .palette = rac.cell.content.color_palette.data };
            if (rac.cell.content_tag == .bg_color_rgb) style.bg_color = .{ .rgb = .{ .r = rac.cell.content.color_rgb.r, .g = rac.cell.content.color_rgb.g, .b = rac.cell.content.color_rgb.b } };
            const wide: usize = if (rac.cell.wide == .wide and x + 1 < end) 2 else 1;
            if (csi.final == '{' and rac.cell.protected) {
                x += wide;
                continue;
            }
            var cp: u21 = ' ';
            if (csi.final == 'x') {
                const value = arg(csi.params, 0, 0);
                if (!(value >= 32 and value <= 126 or value >= 160 and value <= 255)) return;
                cp = @intCast(value);
            }
            if (csi.final == 'r' or csi.final == 't') {
                for (csi.params[@min(4, csi.params.len)..]) |attribute| {
                    const reverse = csi.final == 't';
                    switch (attribute) {
                        0 => {
                            style.flags.bold = if (reverse) !style.flags.bold else false;
                            style.flags.underline = if (reverse and style.flags.underline == .none) .single else .none;
                            style.flags.blink = if (reverse) !style.flags.blink else false;
                            style.flags.inverse = if (reverse) !style.flags.inverse else false;
                        },
                        1, 22 => if (!reverse or attribute == 1) {
                            style.flags.bold = if (reverse) !style.flags.bold else attribute == 1;
                        },
                        4, 24 => if (!reverse or attribute == 4) {
                            style.flags.underline = if (reverse) (if (style.flags.underline == .none) .single else .none) else (if (attribute == 4) .single else .none);
                        },
                        5, 25 => if (!reverse or attribute == 5) {
                            style.flags.blink = if (reverse) !style.flags.blink else attribute == 5;
                        },
                        7, 27 => if (!reverse or attribute == 7) {
                            style.flags.inverse = if (reverse) !style.flags.inverse else attribute == 7;
                        },
                        else => {},
                    }
                }
            }
            const copying = csi.final == 'v' or csi.final == 'r' or csi.final == 't';
            const tx = if (csi.final == 'v') dx + x - left else x;
            const ty = if (csi.final == 'v') dy + y - top else y;
            if (tx >= t.cols or ty >= t.rows) {
                x += wide;
                continue;
            }
            if (copying) {
                var base = rac.cell.codepoint();
                if (base == 0 or rac.cell.wide == .spacer_tail or rac.cell.wide == .spacer_head or rac.cell.wide == .wide and (wide == 1 or tx + 1 == t.cols)) base = ' ';
                const uri = if (rac.cell.hyperlink) page.hyperlink_set.get(page.memory, page.lookupHyperlink(rac.cell).?).uri.slice(page.memory) else null;
                try cell(t, tx, ty, &.{base}, style, rac.cell.protected, uri);
                if (base != ' ' and rac.cell.hasGrapheme()) {
                    const tail = page.lookupGrapheme(rac.cell).?;
                    var complete: std.ArrayList(u21) = .empty;
                    defer complete.deinit(t.gpa());
                    try complete.append(t.gpa(), base);
                    try complete.appendSlice(t.gpa(), tail);
                    try cell(t, tx, ty, complete.items, style, rac.cell.protected, uri);
                }
            } else {
                try cell(t, x, y, &.{cp}, if (csi.final == '{') style else screen.cursor.style, false, null);
            }
            x += if (copying) wide else 1;
        }
    }
}
