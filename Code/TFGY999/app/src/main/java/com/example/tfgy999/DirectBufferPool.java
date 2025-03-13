package com.example.tfgy999;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

public class DirectBufferPool {
    static {
        System.loadLibrary("directbuf"); // 加载本地库
    }

    private static native long nativeAlloc(int size);
    private static native void nativeFree(long ptr, int size);

    private static final int MAX_POOL_SIZE = 10;
    private static final Map<Long, BufferEntry> pool = new HashMap<>();

    private static class BufferEntry {
        ByteBuffer buffer;
        int size;

        BufferEntry(ByteBuffer buffer, int size) {
            this.buffer = buffer;
            this.size = size;
        }
    }

    public static synchronized ByteBuffer acquire(int size) {
        for (Map.Entry<Long, BufferEntry> entry : pool.entrySet()) {
            BufferEntry bufferEntry = entry.getValue();
            if (bufferEntry.buffer.capacity() >= size) {
                pool.remove(entry.getKey());
                bufferEntry.buffer.clear();
                return bufferEntry.buffer;
            }
        }
        long ptr = nativeAlloc(size);
        if (ptr == 0) {
            throw new OutOfMemoryError("Failed to allocate native memory");
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.nativeOrder());
        pool.put(ptr, new BufferEntry(buffer, size)); // 记录指针和大小
        return buffer;
    }

    public static synchronized void release(ByteBuffer buffer) {
        for (Map.Entry<Long, BufferEntry> entry : pool.entrySet()) {
            if (entry.getValue().buffer == buffer) {
                if (pool.size() < MAX_POOL_SIZE) {
                    return; // 保留在池中
                } else {
                    long ptr = entry.getKey();
                    int size = entry.getValue().size;
                    pool.remove(ptr);
                    nativeFree(ptr, size); // 释放本地内存
                    return;
                }
            }
        }
    }

    // 新增 checkLeaks 方法
    public static void checkLeaks() {
        if (!pool.isEmpty()) {
            android.util.Log.w("DirectBufferPool", "检测到潜在的ByteBuffer泄漏：" + pool.size() + "个未释放");
            for (Map.Entry<Long, BufferEntry> entry : pool.entrySet()) {
                android.util.Log.w("DirectBufferPool", "未释放的ByteBuffer: " + entry.getValue().buffer + ", 大小: " + entry.getValue().size);
            }
        } else {
            android.util.Log.i("DirectBufferPool", "没有检测到ByteBuffer泄漏");
        }
    }
}
