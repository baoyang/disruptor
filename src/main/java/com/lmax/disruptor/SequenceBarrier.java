/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;


/**
 * Coordination barrier for tracking the cursor for publishers and sequence of
 * dependent {@link EventProcessor}s for processing a data structure
 * 给消费者使用的接口,主要用途是用于判断某个位置的Event是否已经可用(可以被消费),如果不可用,等待...
 */
public interface SequenceBarrier
{
    /**
     * Wait for the given sequence to be available for consumption.
     * 等待sequence位置的Event变得可以消费.然后消费这个序列。
     * @param sequence to wait for 等待的位置
     * @return the sequence up to which is available 可处理的最大序列号
     * @throws AlertException       if a status change has occurred for the Disruptor
     * @throws InterruptedException if the thread needs awaking on a condition variable.
     * @throws TimeoutException
     */
    long waitFor(long sequence) throws AlertException, InterruptedException, TimeoutException;

    /**
     * Get the current cursor value that can be read.
     * 返回当前可读的游标（一个序号）
     * @return value of the cursor for entries that have been published.
     */
    long getCursor();

    /**
     * The current alert status for the barrier.
     * 当前的barrier是否已经被通知过了
     * @return true if in alert otherwise false.
     */
    boolean isAlerted();

    /**
     * Alert the {@link EventProcessor}s of a status change and stay in this status until cleared.
     * 通知事件处理器状态发生改变，并保持这个状态直到被清除，通知当前的barrier(Event可以被消费了)
     */
    void alert();

    /**
     * Clear the current alert status. 清除当前通知状态
     */
    void clearAlert();

    /**
     * Check if an alert has been raised and throw an {@link AlertException} if it has.
     * 检查通知状态，如果有异常则抛出检查当前barrier的通知状态,如果已经被通知,则抛出异常.
     * @throws AlertException if alert has been raised.
     */
    void checkAlert() throws AlertException;
}
