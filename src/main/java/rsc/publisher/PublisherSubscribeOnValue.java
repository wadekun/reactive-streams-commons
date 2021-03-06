package rsc.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import rsc.documentation.*;
import rsc.flow.*;
import rsc.scheduler.Scheduler;
import rsc.subscriber.SubscriptionHelper;

/**
 * Publisher indicating a scalar/empty source that subscribes on the specified scheduler.
 * 
 * @param <T>
 */
@FusionSupport(input = { FusionMode.NOT_APPLICABLE }, output = { FusionMode.ASYNC })
public final class PublisherSubscribeOnValue<T> extends Px<T> implements Fuseable {

    final T value;
    
    final Scheduler scheduler;

    public PublisherSubscribeOnValue(T value, 
            Scheduler scheduler) {
        this.value = value;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        T v = value;
        if (v == null) {
            ScheduledEmpty parent = new ScheduledEmpty(s);
            s.onSubscribe(parent);
            Disposable f = scheduler.schedule(parent);
            parent.setFuture(f);
        } else {
            s.onSubscribe(new ScheduledScalar<>(s, v, scheduler));
        }
    }

    /**
     * If the source is Callable or ScalarCallable, optimized paths are used instead of the
     * general path.
     * @param <T> the value type
     * @param source the source Publisher
     * @param s the subscriber
     * @param scheduler the scheduler
     * @return true if the optimized path was taken
     */
    @SuppressWarnings("unchecked")
    public static <T> boolean singleScheduleOn(Publisher<? extends T> source, Subscriber<? super T> s, Scheduler scheduler) {
        if (source instanceof Callable) {
            if (!scalarScheduleOn(source, s, scheduler)) {
                PublisherCallableSubscribeOn.subscribe((Callable<T>)source, s, scheduler);
            }
            return true;
        }
        return false;
    }
    
    public static <T> boolean scalarScheduleOn(Publisher<? extends T> source, Subscriber<? super T> s, Scheduler scheduler) {
        if (source instanceof ScalarCallable) {
            @SuppressWarnings("unchecked")
            Fuseable.ScalarCallable<T> supplier = (Fuseable.ScalarCallable<T>) source;
            
            T v = supplier.call();
            
            if (v == null) {
                ScheduledEmpty parent = new ScheduledEmpty(s);
                s.onSubscribe(parent);
                Disposable f = scheduler.schedule(parent);
                parent.setFuture(f);
            } else {
                s.onSubscribe(new ScheduledScalar<>(s, v, scheduler));
            }
            return true;
        }
        return false;
    }
    

    static final class ScheduledScalar<T>
    implements Fuseable.QueueSubscription<T>, Runnable, Producer, Loopback {

        final Subscriber<? super T> actual;

        final T value;

        final Scheduler scheduler;

        volatile int once;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<ScheduledScalar> ONCE =
        AtomicIntegerFieldUpdater.newUpdater(ScheduledScalar.class, "once");

        volatile Disposable future;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<ScheduledScalar, Disposable> FUTURE =
        AtomicReferenceFieldUpdater.newUpdater(ScheduledScalar.class, Disposable.class, "future");

        static final Disposable CANCELLED = () -> { };

        static final Disposable FINISHED = () -> { };

        int fusionState;

        static final int NOT_FUSED = 0;
        static final int NO_VALUE = 1;
        static final int HAS_VALUE = 2;
        static final int COMPLETE = 3;

        public ScheduledScalar(Subscriber<? super T> actual, T value, Scheduler scheduler) {
            this.actual = actual;
            this.value = value;
            this.scheduler = scheduler;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                if (ONCE.compareAndSet(this, 0, 1)) {
                    Disposable f = scheduler.schedule(this);
                    if (!FUTURE.compareAndSet(this, null, f)) {
                        if (future != FINISHED && future != CANCELLED) {
                            f.dispose();
                        }
                    }
                }
            }
        }

        @Override
        public void cancel() {
            ONCE.lazySet(this, 1);
            Disposable f = future;
            if (f != CANCELLED && future != FINISHED) {
                f = FUTURE.getAndSet(this, CANCELLED);
                if (f != null && f != CANCELLED && f != FINISHED) {
                    f.dispose();
                }
            }
        }

        @Override
        public void run() {
            try {
                if (fusionState == NO_VALUE) {
                    fusionState = HAS_VALUE;
                }
                actual.onNext(value);
                actual.onComplete();
            } finally {
                FUTURE.lazySet(this, FINISHED);
            }
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object connectedInput() {
            return scheduler;
        }

        @Override
        public Object connectedOutput() {
            return value;
        }

        @Override
        public int requestFusion(int requestedMode) {
            if ((requestedMode & Fuseable.ASYNC) != 0) {
                fusionState = NO_VALUE;
                return Fuseable.ASYNC;
            }
            return Fuseable.ASYNC;
        }

        @Override
        public T poll() {
            if (fusionState == HAS_VALUE) {
                fusionState = COMPLETE;
                return value;
            }
            return null;
        }

        @Override
        public boolean isEmpty() {
            return fusionState != HAS_VALUE;
        }

        @Override
        public int size() {
            return isEmpty() ? 0 : 1;
        }

        @Override
        public void clear() {
            fusionState = COMPLETE;
        }
    }

    static final class ScheduledEmpty implements Fuseable.QueueSubscription<Void>, Runnable, Producer, Loopback {
        final Subscriber<?> actual;

        volatile Disposable future;
        static final AtomicReferenceFieldUpdater<ScheduledEmpty, Disposable> FUTURE =
                AtomicReferenceFieldUpdater.newUpdater(ScheduledEmpty.class, Disposable.class, "future");

        static final Disposable CANCELLED = () -> { };

        static final Disposable FINISHED = () -> { };

        public ScheduledEmpty(Subscriber<?> actual) {
            this.actual = actual;
        }

        @Override
        public void request(long n) {
            SubscriptionHelper.validate(n);
        }

        @Override
        public void cancel() {
            Disposable f = future;
            if (f != CANCELLED && f != FINISHED) {
                f = FUTURE.getAndSet(this, CANCELLED);
                if (f != null && f != CANCELLED && f != FINISHED) {
                    f.dispose();
                }
            }
        }

        @Override
        public void run() {
            try {
                actual.onComplete();
            } finally {
                FUTURE.lazySet(this, FINISHED);
            }
        }

        void setFuture(Disposable f) {
            if (!FUTURE.compareAndSet(this, null, f)) {
                Disposable a = future;
                if (a != FINISHED && a != CANCELLED) {
                    f.dispose();
                }
            }
        }

        @Override
        public Object connectedInput() {
            return null; // FIXME value?
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public int requestFusion(int requestedMode) {
            if ((requestedMode & Fuseable.ASYNC) != 0) {
                return Fuseable.ASYNC;
            }
            return Fuseable.ASYNC;
        }

        @Override
        public Void poll() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public void clear() {
            // nothing to do
        }
    }

}
