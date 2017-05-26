package com.lmax.disruptor;

/**
 * 实现一个有序的存储结构，也就是RingBuffer的一个特性。
 */
public interface Sequenced
{
    /**
     * The capacity of the data structure to hold entries.
     *
     * @return the size of the RingBuffer. 返回当前RingBuffer的大小.
     */
    int getBufferSize();

    /**
     * Has the buffer got capacity to allocate another sequence.  This is a concurrent
     * method so the response should only be taken as an indication of available capacity.
     * 判断当前的RingBuffer是否还有足够的空间可以容纳requiredCapacity个Event.
     * @param requiredCapacity in the buffer
     * @return true if the buffer has the capacity to allocate the next sequence otherwise false.
     */
    boolean hasAvailableCapacity(final int requiredCapacity);

    /**
     * Get the remaining capacity for this sequencer.
     * 返回当前RingBuffer可用的空间数目.
     * @return The number of slots remaining.
     */
    long remainingCapacity();

    /**
     * Claim the next event in sequence for publishing.
     * 返回当前RingBuffer上可以给生产者发布Event的位置的序号
     * @return the claimed sequence value
     */
    long next();

    /**
     * Claim the next n events in sequence for publishing.  This is for batch event producing.  Using batch producing
     * requires a little care and some math.
     * <pre> 向RingBuffer申请n个可用空间给生产者发布Event.主要用于批量发布的场景,使用该函数需要做一些额外的计算,
     * int n = 10;
     * long hi = sequencer.next(n); //最高位
     * long lo = hi - (n - 1); //最低位
     * for (long sequence = lo; sequence &lt;= hi; sequence++) {
     *     // Do work.
     * }
     * sequencer.publish(lo, hi); 最后再通知消费者这些区间内的数据可以被消费:
     * </pre>
     *
     * @param n the number of sequences to claim
     * @return the highest claimed sequence value
     */
    long next(int n);

    /**
     * Attempt to claim the next event in sequence for publishing.  Will return the
     * number of the slot if there is at least <code>requiredCapacity</code> slots
     * available.
     * 尝试向RingBuffer申请一个可用空间, 如果有,则返回该可用空间的位置序号,否则抛出异常.这个是无阻塞的方法。
     * @return the claimed sequence value
     * @throws InsufficientCapacityException
     */
    long tryNext() throws InsufficientCapacityException;

    /**
     * Attempt to claim the next n events in sequence for publishing.  Will return the
     * highest numbered slot if there is at least <code>requiredCapacity</code> slots
     * available.  Have a look at {@link Sequencer#next()} for a description on how to
     * use this method.
     *尝试向RingBuffer申请n个可用空间,如果有,则返回这些可用空间中最后一个空间的位置序号,否则抛出异常.这个是无阻塞的方法。
     * @param n the number of sequences to claim
     * @return the claimed sequence value
     * @throws InsufficientCapacityException
     */
    long tryNext(int n) throws InsufficientCapacityException;

    /**
     * Publishes a sequence. Call when the event has been filled.
     *发布该位置的Event(通知消费者可以消费了), 需要注意的是调用该函数之前需要先将该位置的数据填充上.
     * @param sequence
     */
    void publish(long sequence);

    /**
     * Batch publish sequences.  Called when all of the events have been filled.
     *发布[lo, hi]区间的Event(通知消费者可以消费了),需要注意的是调用该函数之前需要先将这些位置的数据填充上
     * @param lo first sequence number to publish
     * @param hi last sequence number to publish
     */
    void publish(long lo, long hi);
}