package rsc.publisher;

import java.util.Objects;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import rsc.documentation.BackpressureMode;
import rsc.documentation.BackpressureSupport;
import rsc.documentation.FusionMode;
import rsc.documentation.FusionSupport;
import rsc.flow.*;
import rsc.subscriber.DeferredScalarSubscriber;
import rsc.subscriber.SubscriptionHelper;

/**
 * Emits a scalar value if the source sequence turns out to be empty.
 *
 * @param <T> the value type
 */
@BackpressureSupport(input = BackpressureMode.UNBOUNDED, output = BackpressureMode.BOUNDED)
@FusionSupport(input = { FusionMode.NONE }, output = { FusionMode.NONE })
public final class PublisherDefaultIfEmpty<T> extends PublisherSource<T, T> {

    final T value;

    public PublisherDefaultIfEmpty(Publisher<? extends T> source, T value) {
        super(source);
        this.value = Objects.requireNonNull(value, "value");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new PublisherDefaultIfEmptySubscriber<>(s, value));
    }

    static final class PublisherDefaultIfEmptySubscriber<T>
            extends DeferredScalarSubscriber<T, T>
            implements Receiver {

        Subscription s;

        boolean hasValue;

        public PublisherDefaultIfEmptySubscriber(Subscriber<? super T> actual, T value) {
            super(actual);
            this.value = value;
        }

        @Override
        public void request(long n) {
            super.request(n);
            s.request(n);
        }

        @Override
        public void cancel() {
            super.cancel();
            s.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                subscriber.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            if (!hasValue) {
                hasValue = true;
            }

            subscriber.onNext(t);
        }

        @Override
        public void onComplete() {
            if (hasValue) {
                subscriber.onComplete();
            } else {
                complete(value);
            }
        }

        @Override
        public void setValue(T value) {
            // value is constant
        }

        @Override
        public Object upstream() {
            return s;
        }

        @Override
        public Object connectedInput() {
            return value;
        }
        
        @Override
        public int requestFusion(int requestedMode) {
            return Fuseable.NONE; // prevent fusion because of the upstream
        }
    }
}
