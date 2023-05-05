/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.jank;

import static com.android.internal.jank.FrameTracker.REASON_END_NORMAL;

import android.annotation.ColorInt;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.os.Trace;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.WindowCallbacks;

import com.android.internal.jank.FrameTracker.Reasons;
import com.android.internal.jank.InteractionJankMonitor.CujType;

/**
 * An overlay that uses WindowCallbacks to draw the names of all running CUJs to the window
 * associated with one of the CUJs being tracked. There's no guarantee which window it will
 * draw to. NOTE: sometimes the CUJ names will remain displayed on the screen longer than they
 * are actually running.
 * <p>
 * CUJ names will be drawn as follows:
 * <ul>
 * <li> Normal text indicates the CUJ is currently running
 * <li> Grey text indicates the CUJ ended normally and is no longer running
 * <li> Red text with a strikethrough indicates the CUJ was canceled or ended abnormally
 * </ul>
 */
class InteractionMonitorDebugOverlay implements WindowCallbacks {
    private static final int REASON_STILL_RUNNING = -1000;
    // Sparse array where the key in the CUJ and the value is the session status, or null if
    // it's currently running
    private final SparseIntArray mRunningCujs = new SparseIntArray();
    private FrameTracker.ViewRootWrapper mViewRoot = null;
    private final Paint mDebugPaint;
    private final Paint.FontMetrics mDebugFontMetrics;
    // Used to display the overlay in a different color and position for different processes.
    // Otherwise, two overlays will overlap and be difficult to read.
    private final int mBgColor;
    private final double mYOffset;
    private final String mPackageName;

    InteractionMonitorDebugOverlay(@ColorInt int bgColor, double yOffset) {
        mBgColor = bgColor;
        mYOffset = yOffset;
        mDebugPaint = new Paint();
        mDebugPaint.setAntiAlias(false);
        mDebugFontMetrics = new Paint.FontMetrics();
        final Context context = ActivityThread.currentApplication();
        mPackageName = context.getPackageName();
    }

    void dispose() {
        if (mViewRoot != null) {
            mViewRoot.removeWindowCallbacks(this);
            forceRedraw();
        }
        mViewRoot = null;
    }

    private boolean attachViewRootIfNeeded(FrameTracker.ViewRootWrapper viewRoot) {
        if (mViewRoot == null && viewRoot != null) {
            mViewRoot = viewRoot;
            viewRoot.addWindowCallbacks(this);
            forceRedraw();
            return true;
        }
        return false;
    }

    private float getWidthOfLongestCujName(int cujFontSize) {
        mDebugPaint.setTextSize(cujFontSize);
        float maxLength = 0;
        for (int i = 0; i < mRunningCujs.size(); i++) {
            String cujName = InteractionJankMonitor.getNameOfCuj(mRunningCujs.keyAt(i));
            float textLength = mDebugPaint.measureText(cujName);
            if (textLength > maxLength) {
                maxLength = textLength;
            }
        }
        return maxLength;
    }

    private float getTextHeight(int textSize) {
        mDebugPaint.setTextSize(textSize);
        mDebugPaint.getFontMetrics(mDebugFontMetrics);
        return mDebugFontMetrics.descent - mDebugFontMetrics.ascent;
    }

    private int dipToPx(int dip) {
        if (mViewRoot != null) {
            return mViewRoot.dipToPx(dip);
        } else {
            return dip;
        }
    }

    private void forceRedraw() {
        if (mViewRoot != null) {
            mViewRoot.requestInvalidateRootRenderNode();
            mViewRoot.getView().invalidate();
        }
    }

    void onTrackerRemoved(@CujType int removedCuj, @Reasons int reason,
                          SparseArray<FrameTracker> runningTrackers) {
        mRunningCujs.put(removedCuj, reason);
        // If REASON_STILL_RUNNING is not in mRunningCujs, then all CUJs have ended
        if (mRunningCujs.indexOfValue(REASON_STILL_RUNNING) < 0) {
            mRunningCujs.clear();
            dispose();
        } else {
            boolean needsNewViewRoot = true;
            if (mViewRoot != null) {
                // Check to see if this viewroot is still associated with one of the running
                // trackers
                for (int i = 0; i < runningTrackers.size(); i++) {
                    if (mViewRoot.equals(
                            runningTrackers.valueAt(i).getViewRoot())) {
                        needsNewViewRoot = false;
                        break;
                    }
                }
            }
            if (needsNewViewRoot) {
                dispose();
                for (int i = 0; i < runningTrackers.size(); i++) {
                    if (attachViewRootIfNeeded(runningTrackers.valueAt(i).getViewRoot())) {
                        break;
                    }
                }
            } else {
                forceRedraw();
            }
        }
    }

    void onTrackerAdded(@CujType int addedCuj, FrameTracker.ViewRootWrapper viewRoot) {
        // Use REASON_STILL_RUNNING (not technically one of the '@Reasons') to indicate the CUJ
        // is still running
        mRunningCujs.put(addedCuj, REASON_STILL_RUNNING);
        attachViewRootIfNeeded(viewRoot);
        forceRedraw();
    }

    @Override
    public void onWindowSizeIsChanging(Rect newBounds, boolean fullscreen,
                                       Rect systemInsets, Rect stableInsets) {
    }

    @Override
    public void onWindowDragResizeStart(Rect initialBounds, boolean fullscreen,
                                        Rect systemInsets, Rect stableInsets) {
    }

    @Override
    public void onWindowDragResizeEnd() {
    }

    @Override
    public boolean onContentDrawn(int offsetX, int offsetY, int sizeX, int sizeY) {
        return false;
    }

    @Override
    public void onRequestDraw(boolean reportNextDraw) {
    }

    @Override
    public void onPostDraw(RecordingCanvas canvas) {
        Trace.beginSection("InteractionJankMonitor#drawDebug");
        final int padding = dipToPx(5);
        final int h = canvas.getHeight();
        final int w = canvas.getWidth();
        // Draw sysui CUjs near the bottom of the screen so they don't overlap with the shade,
        // and draw launcher CUJs near the top of the screen so they don't overlap with gestures
        final int dy = (int) (h * mYOffset);
        int packageNameFontSize = dipToPx(12);
        int cujFontSize = dipToPx(18);
        final float cujNameTextHeight = getTextHeight(cujFontSize);
        final float packageNameTextHeight = getTextHeight(packageNameFontSize);
        float maxLength = getWidthOfLongestCujName(cujFontSize);

        final int dx = (int) ((w - maxLength) / 2f);
        canvas.translate(dx, dy);
        // Draw background rectangle for displaying the text showing the CUJ name
        mDebugPaint.setColor(mBgColor);
        canvas.drawRect(
                -padding * 2, // more padding on top so we can draw the package name
                -padding,
                padding * 2 + maxLength,
                padding * 2 + packageNameTextHeight + cujNameTextHeight * mRunningCujs.size(),
                mDebugPaint);
        mDebugPaint.setTextSize(packageNameFontSize);
        mDebugPaint.setColor(Color.BLACK);
        mDebugPaint.setStrikeThruText(false);
        canvas.translate(0, packageNameTextHeight);
        canvas.drawText("package:" + mPackageName, 0, 0, mDebugPaint);
        mDebugPaint.setTextSize(cujFontSize);
        // Draw text for CUJ names
        for (int i = 0; i < mRunningCujs.size(); i++) {
            int status = mRunningCujs.valueAt(i);
            if (status == REASON_STILL_RUNNING) {
                mDebugPaint.setColor(Color.BLACK);
                mDebugPaint.setStrikeThruText(false);
            } else if (status == REASON_END_NORMAL) {
                mDebugPaint.setColor(Color.GRAY);
                mDebugPaint.setStrikeThruText(false);
            } else {
                // Cancelled, or otherwise ended for a bad reason
                mDebugPaint.setColor(Color.RED);
                mDebugPaint.setStrikeThruText(true);
            }
            String cujName = InteractionJankMonitor.getNameOfCuj(mRunningCujs.keyAt(i));
            canvas.translate(0, cujNameTextHeight);
            canvas.drawText(cujName, 0, 0, mDebugPaint);
        }
        Trace.endSection();
    }
}
