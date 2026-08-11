/*
 * Copyright (C) 2015, Collabora Ltd.
 *
 * Licensed under the GNU Lesser General Public License, version 2.1.
 */
package org.freedesktop.gstreamer.androidmedia;

import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;

public class GstAmcOnFrameAvailableListener implements OnFrameAvailableListener {
    private long context = 0;

    @Override
    public synchronized void onFrameAvailable(SurfaceTexture surfaceTexture) {
        native_onFrameAvailable(context, surfaceTexture);
    }

    public synchronized long getContext() {
        return context;
    }

    public synchronized void setContext(long value) {
        context = value;
    }

    private native void native_onFrameAvailable(long context, SurfaceTexture surfaceTexture);
}
