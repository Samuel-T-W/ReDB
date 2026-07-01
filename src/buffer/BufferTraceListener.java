package buffer;

/** Optional observer for buffer-pool activity during traced query runs. */
public interface BufferTraceListener {
    default void onBufferHit(String fileId, int pageId, int frameId, boolean dirty, int pinCount) {}

    default void onBufferMiss(String fileId, int pageId) {}

    default void onPageLoad(String fileId, int pageId, int frameId, boolean dirty, int pinCount) {}

    default void onPageEvict(
            String fileId,
            int pageId,
            int frameId,
            boolean dirty,
            int pinCount) {}

    default void onBufferFlush(String fileId, int pageId) {}
}
