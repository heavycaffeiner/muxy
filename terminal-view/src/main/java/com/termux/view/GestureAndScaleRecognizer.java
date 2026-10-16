package com.termux.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

/** A combination of {@link GestureDetector} and {@link ScaleGestureDetector}. */
final class GestureAndScaleRecognizer {

    public interface Listener {
        boolean onSingleTapUp(MotionEvent e);

        boolean onDoubleTap(MotionEvent e);

        boolean onScroll(MotionEvent e2, float dx, float dy);

        boolean onFling(MotionEvent e, float velocityX, float velocityY);

        boolean onScale(float focusX, float focusY, float scale);

        boolean onDown(float x, float y);

        boolean onUp(MotionEvent e);

        void onLongPress(MotionEvent e);
    }

    private final GestureDetector mGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    final Listener mListener;
    boolean isAfterLongPress;
    private boolean mDoubleTap;
    private boolean mTapCancelled;
    private float mTapX, mTapY;
    private int mTapDevice, mTapSource;
    private final int mTouchSlop;

    public GestureAndScaleRecognizer(Context context, Listener listener) {
        mListener = listener;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                return mListener.onScroll(e2, dx, dy);
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                return mListener.onFling(e2, velocityX, velocityY);
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return mListener.onDown(e.getX(), e.getY());
            }

            @Override
            public void onLongPress(MotionEvent e) {
                mListener.onLongPress(e);
                isAfterLongPress = true;
            }
        }, null, true /* ignoreMultitouch */);

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return !mTapCancelled && !isAfterLongPress && mListener.onSingleTapUp(e);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                mDoubleTap = true;
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP && mDoubleTap && !mTapCancelled
                        && !isAfterLongPress
                        && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
                    mDoubleTap = false;
                    return mListener.onDoubleTap(e);
                }
                return true;
            }
        });

        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                return mListener.onScale(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
            }
        });
        mScaleDetector.setQuickScaleEnabled(false);
    }

    public void onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (event.getDeviceId() != mTapDevice || event.getSource() != mTapSource) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                mGestureDetector.onTouchEvent(cancel);
                cancel.recycle();
            }
            isAfterLongPress = false;
            mDoubleTap = false;
            mTapCancelled = false;
            mTapX = event.getX();
            mTapY = event.getY();
            mTapDevice = event.getDeviceId();
            mTapSource = event.getSource();
        } else if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() != 1) {
            mTapCancelled = true;
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            for (int i = 0; i <= event.getHistorySize(); i++) {
                float x = i == event.getHistorySize() ? event.getX() : event.getHistoricalX(i);
                float y = i == event.getHistorySize() ? event.getY() : event.getHistoricalY(i);
                float dx = x - mTapX, dy = y - mTapY;
                if (dx * dx + dy * dy > (float) mTouchSlop * mTouchSlop) mTapCancelled = true;
            }
        }
        mGestureDetector.onTouchEvent(event);
        mScaleDetector.onTouchEvent(event);
        switch (action) {
            case MotionEvent.ACTION_UP:
                if (!isAfterLongPress) {
                    // This behaviour is desired when in e.g. vim with mouse events, where we do not
                    // want to move the cursor when lifting finger after a long press.
                    mListener.onUp(event);
                }
                break;
        }
    }

    public boolean isInProgress() {
        return mScaleDetector.isInProgress();
    }

}
