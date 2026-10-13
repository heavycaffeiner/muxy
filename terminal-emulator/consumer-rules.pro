-keep class com.termux.terminal.NativeTerminal {
    native <methods>;
}

-keep interface com.termux.terminal.NativeTerminal$Listener {
    *;
}

-keepclassmembers class * implements com.termux.terminal.NativeTerminal$Listener {
    public void onWrite(byte[]);
    public void onBell();
    public void onTitle(byte[]);
    public void onClipboard(byte[]);
    public void onEvent(int, byte[]);
}
