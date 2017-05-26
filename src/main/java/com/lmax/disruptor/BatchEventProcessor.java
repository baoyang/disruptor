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

import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 * <p>
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    private final AtomicBoolean running = new AtomicBoolean(false); //表示当前事件处理器的运行状态
    private ExceptionHandler<? super T> exceptionHandler = new FatalExceptionHandler();  //异常处理器
    private final DataProvider<T> dataProvider;  //数据提供者(RingBuffer)
    private final SequenceBarrier sequenceBarrier; //序列栅栏
    private final EventHandler<? super T> eventHandler; //真正处理事件的回调接口
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE); //事件处理器使用的序列。
    private final TimeoutHandler timeoutHandler;   //超时处理器
    private final BatchStartAware batchStartAware;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
                (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
                (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt() //暂停
    {
        running.set(false);//设置运行状态为false
        sequenceBarrier.alert();//通知序列栅栏
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
        if (!running.compareAndSet(false, true))  // 确保一次只有一个线程执行此方法，这样访问自身的序号就不要加锁
        {
            throw new IllegalStateException("Thread is already running");
        }
        sequenceBarrier.clearAlert(); //清空alerted标志

        notifyStart(); //如果实现了LifecycleAware，触发onStart事件

        T event = null;
        long nextSequence = sequence.get() + 1L;
        try
        {
            while (true)
            {
                try
                {
                    // 从它的前置序号关卡获取下一个可处理的事件序号。
                    // 如果这个事件处理器不依赖于其他的事件处理器，则前置关卡就是生产者序号；
                    // 如果这个事件处理器依赖于1个或多个事件处理器，那么这个前置关卡就是这些前置事件处理器中最慢的一个。
                    // 通过这样，可以确保事件处理器不会超前处理地事件。
                    final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                    if (batchStartAware != null)
                    {
                        batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                    }

                    while (nextSequence <= availableSequence)  // 处理一批事件
                    {
                        event = dataProvider.get(nextSequence);
                        eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                        nextSequence++;
                    }

                    sequence.set(availableSequence); // 设置它自己最后处理的事件序号,这样依赖于它的处理器可以它处理刚处理过的事件
                }
                catch (final TimeoutException e)
                {
                    notifyTimeout(sequence.get()); // 获取事件序号超时处理
                }
                catch (final AlertException ex)
                {
                    if (!running.get()) // 处理通知事件；检测是否要停止，如果非则继续处理事件
                    {
                        break;
                    }
                }
                catch (final Throwable ex) //如果发生异常，会调用exceptionHandler进行处理，流程并不会中断
                {
                    exceptionHandler.handleEventException(ex, nextSequence, event);
                    sequence.set(nextSequence);
                    nextSequence++;
                }
            }
        }
        finally
        {
            notifyShutdown();
            running.set(false);
        }
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}