/*****************************************************************************
 * Copyright (C) google.com                                                *
 * ------------------------------------------------------------------------- *
 * Licensed under the Apache License, Version 2.0 (the "License");           *
 * you may not use this file except in compliance with the License.          *
 * You may obtain a copy of the License at                                   *
 *                                                                           *
 * http://www.apache.org/licenses/LICENSE-2.0                                *
 *                                                                           *
 * Unless required by applicable law or agreed to in writing, software       *
 * distributed under the License is distributed on an "AS IS" BASIS,         *
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 * See the License for the specific language governing permissions and       *
 * limitations under the License.                                            *
 *****************************************************************************/
package com.google.mu.util;

import static com.google.mu.util.Utils.cast;
import static com.google.mu.util.Utils.propagateIfUnchecked;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.google.mu.function.CheckedBiFunction;
import com.google.mu.function.CheckedConsumer;
import com.google.mu.function.CheckedFunction;
import com.google.mu.function.CheckedSupplier;

/**
 * Class that wraps checked exceptions and tunnels them through stream operations or future graphs.
 *
 * <p>The idea is to wrap checked exceptions inside {@code Stream<Maybe<T, E>>}, then {@code map()},
 * {@code flatMap()} and {@code filter()} away through normal stream operations.
 * Exception is only thrown during terminal operations.
 * For example, the following code fetches and runs pending jobs using a stream of {@code Maybe}:
 *
 * <pre>{@code
 *   private Job fetchJob(long jobId) throws IOException;
 *
 *   void runPendingJobs() throws IOException {
 *     Stream<Maybe<Job, IOException>> stream = activeJobIds.stream()
 *         .map(maybe(this::fetchJob))
 *         .filter(byValue(Job::isPending));
 *     Iterate.through(stream, m -> m.orElseThrow(IOException::new).runJob());
 *   }
 * }</pre>
 *
 * When it comes to futures, the following asynchronous code example handles exceptions type safely
 * using {@link #catchException Maybe.catchException()}:
 * <pre>{@code
 *   CompletionStage<User> assumeAnonymousIfNotAuthenticated(CompletionStage<User> stage) {
 *     CompletionStage<Maybe<User, AuthenticationException>> authenticated =
 *         Maybe.catchException(AuthenticationException.class, stage);
 *     return authenticated.thenApply(maybe -> maybe.orElse(e -> new AnonymousUser()));
 *   }
 * }</pre>
 */
public abstract class Maybe<T, E extends Throwable> {
  private static final Logger logger = Logger.getLogger(Maybe.class.getName());

  /**
   * Creates a {@code Maybe} for {@code value}.
   *
   * @param value can be null
   */
  public static <T, E extends Throwable> Maybe<T, E> of(T value) {
    return new Success<>(value);
  }

  /**
   * Creates an exceptional {@code Maybe} for {@code exception}.
   *
   * <p>If {@code exception} is an {@link InterruptedException}, the current thread is
   * re-interrupted as a standard practice to avoid swallowing the interruption signal.
   */
  public static <T, E extends Throwable> Maybe<T, E> except(E exception) {
    return new Failure<>(exception);
  }

  /**
   * Maps {@code this} using {@code function} unless it wraps exception.
   */
  public abstract <T2> Maybe<T2, E> map(Function<? super T, ? extends T2> function);

  /**
   * Flat maps {@code this} using {@code f} unless it wraps exception.
   */
  public abstract <T2> Maybe<T2, E> flatMap(Function<? super T, Maybe<T2, E>> function);

  /** Returns true unless this is exceptional. */
  public abstract boolean isPresent();

  /** Applies {@code consumer} if {@code this} is present. Returns {@code this}. */
  public abstract Maybe<T, E> ifPresent(Consumer<? super T> consumer);

  /** Either returns the encapsulated value, or translates exception using {@code function}. */
  public abstract <X extends Throwable> T orElse(
      CheckedFunction<? super E, ? extends T, X> function) throws X;

  /**
   * Returns the encapsulated value or throws exception.
   *
   * <p>If {@code this} encapsulates an exception, a wrapper exception of type {@code E} is thrown
   * to capture the caller's stack trace with the original exception as the cause.
   *
   * <p>By default, the wrapper exception is created through serialization to ensure exception
   * type and state transparency to the caller code. But, serialization is inefficient and not all
   * exceptions support it. When serialization fails, the original exception will be thrown as is.
   *
   * <p>Consider to use {@link #orElseThrow(Function)} to more efficiently and more reliably wrap
   * exceptions, for example: {@code orElseThrow(IOException::new)}.
   *
   * <p>In the more rare cases where throwing the original exception is required, use {@code
   * orElseThrow(e -> e)}.
   *
   * <p>If {@link InterruptedException} is thrown, the current thread's {@link Thread#interrupted()}
   * bit is cleared because it's what most code expects when they catch an
   * {@code InterruptedException}.
   *
   * <p>No exception wrapping is attempted for {@code InterruptedException}.
   */
  public final T orElseThrow() throws E {
    return orElseThrow(Maybe::defaultWrapException);
  }

  /**
   * Either returns success value, or throws exception created by {@code exceptionWrapper}.
   *
   * <p>It's recommended for {@code exceptionWrapper} to wrap the original exception as the cause.
   */
  public final T orElseThrow(Function<? super E, ? extends E> exceptionWrapper) throws E {
    requireNonNull(exceptionWrapper);
    return orElse(e -> {
      throw exceptionWrapper.apply(e);
    });
  }

  /**
   * Catches and handles exception with {@code handler}, and then skips it in the returned
   * {@code Stream}. This is specially useful in a {@link Stream} chain to handle and then ignore
   * exceptional results.
   */
  public final <X extends Throwable> Stream<T> catching(
      CheckedConsumer<? super E, ? extends X> handler) throws X {
    requireNonNull(handler);
    return map(Stream::of).orElse(e -> {
      handler.accept(e);
      return Stream.empty();
    });
  }

  /**
   * Turns {@code condition} to a {@code Predicate} over {@code Maybe}. The returned predicate
   * matches any {@code Maybe} with a matching value, as well as any exceptional {@code Maybe} so
   * as not to accidentally swallow exceptions.
   */
  public static <T, E extends Throwable> Predicate<Maybe<T, E>> byValue(
      Predicate<? super T> condition) {
    requireNonNull(condition);
    return maybe -> maybe.map(condition::test).orElse(e -> true);
  }

  /**
   * Invokes {@code supplier} and wraps the returned object or thrown exception in a
   * {@code Maybe<T, E>}.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <T, E extends Throwable> Maybe<T, E> maybe(
      CheckedSupplier<? extends T, ? extends E> supplier) {
    requireNonNull(supplier);
    try {
      return of(supplier.get());
    } catch (Throwable e) {
      // CheckedSupplier<T, E> can only throw unchecked or E.
      @SuppressWarnings("unchecked")
      E exception = (E) propagateIfUnchecked(e);
      return except(exception);
    }
  }

  /**
   * Invokes {@code supplier} and wraps the returned {@code Stream<T>} or thrown exception into a
   * stream of {@code Maybe<T, E>}.
   *
   * <p>Useful to be passed to {@link Stream#flatMap}.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <T, E extends Throwable> Stream<Maybe<T, E>> maybeStream(
      CheckedSupplier<? extends Stream<? extends T>, E> supplier) {
    return maybe(supplier).map(s -> s.map(Maybe::<T, E>of)).orElse(e -> Stream.of(except(e)));
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <F, T, E extends Throwable> Function<F, Maybe<T, E>> maybe(
      CheckedFunction<? super F, ? extends T, E> function) {
    requireNonNull(function);
    return from -> maybe(()->function.apply(from));
  }

  /**
   * Wraps {@code function} that returns {@code Stream<T>} to one that returns
   * {@code Stream<Maybe<T, E>>} with exceptions of type {@code E} wrapped.
   *
   * <p>Useful to be passed to {@link Stream#flatMap}.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <F, T, E extends Throwable> Function<F, Stream<Maybe<T, E>>> maybeStream(
      CheckedFunction<? super F, ? extends Stream<? extends T>, E> function) {
    Function<F, Maybe<Stream<? extends T>, E>> wrapped = maybe(function);
    return wrapped.andThen(Maybe::maybeStream);
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <A, B, T, E extends Throwable> BiFunction<A, B, Maybe<T, E>> maybe(
      CheckedBiFunction<? super A, ? super B, ? extends T, ? extends E> function) {
    requireNonNull(function);
    return (a, b) -> maybe(()->function.apply(a, b));
  }

  /**
   * Wraps {@code function} that returns {@code Stream<T>} to one that returns
   * {@code Stream<Maybe<T, E>>} with exceptions of type {@code E} wrapped.
   *
   * <p>Useful to be passed to {@link Stream#flatMap}.
   *
   * <p>Unchecked exceptions will be immediately propagated without being wrapped.
   */
  public static <A, B, T, E extends Throwable> BiFunction<A, B, Stream<Maybe<T, E>>> maybeStream(
      CheckedBiFunction<? super A, ? super B, ? extends Stream<? extends T>, ? extends E> function) {
    BiFunction<A, B, Maybe<Stream<? extends T>, E>> wrapped = maybe(function);
    return wrapped.andThen(Maybe::maybeStream);
  }

  /**
   * Wraps {@code supplier} to be used for a stream of Maybe.
   *
   * <p>Normally one should use {@link #maybe(CheckedSupplier)} unless {@code E} is an unchecked
   * exception type.
   *
   * <p>For GWT code, wrap the supplier manually, as in:
   *
   * <pre>{@code
   *   private static <T> Maybe<T, FooException> foo(
   *       CheckedSupplier<T, FooException> supplier) {
   *     try {
   *       return Maybe.of(supplier.get());
   *     } catch (FooException e) {
   *       return Maybe.except(e);
   *     }
   *   }
   * }</pre>
   */
  public static <T, E extends Throwable> Maybe<T, E> maybe(
      CheckedSupplier<? extends T, ? extends E> supplier, Class<E> exceptionType) {
    requireNonNull(supplier);
    requireNonNull(exceptionType);
    try {
      return of(supplier.get());
    } catch (Throwable e) {
      return cast(e, exceptionType)
          .map(Maybe::<T, E>except)
          .orElseThrow(() -> new AssertionError(propagateIfUnchecked(e)));
    }
  }

  /**
   * Invokes {@code supplier} and wraps the returned {@code Stream<T>} or thrown exception into a
   * stream of {@code Maybe<T, E>}.
   */
  public static <T, E extends Throwable> Stream<Maybe<T, E>> maybeStream(
      CheckedSupplier<? extends Stream<? extends T>, ? extends E> supplier,
      Class<E> exceptionType) {
    return maybeStream(maybe(supplier, exceptionType));
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Normally one should use {@link #maybe(CheckedFunction)} unless {@code E} is an unchecked
   * exception type.
   *
   * <p>For GWT code, wrap the function manually, as in:
   *
   * <pre>{@code
   *   private static <F, T> Function<F, Maybe<T, FooException>> foo(
   *       CheckedFunction<F, T, FooException> function) {
   *     return from -> {
   *       try {
   *         return Maybe.of(function.apply(from));
   *       } catch (FooException e) {
   *         return Maybe.except(e);
   *       }
   *     };
   *   }
   * }</pre>
   */
  public static <F, T, E extends Throwable> Function<F, Maybe<T, E>> maybe(
      CheckedFunction<? super F, ? extends T, ? extends E> function, Class<E> exceptionType) {
    requireNonNull(function);
    requireNonNull(exceptionType);
    return from -> maybe(() -> function.apply(from), exceptionType);
  }

  /**
   * Wraps {@code function} that returns {@code Stream<T>} to one that returns
   * {@code Stream<Maybe<T, E>>} with exceptions of type {@code E} wrapped.
   */
  public static <F, T, E extends Throwable> Function<F, Stream<Maybe<T, E>>> maybeStream(
      CheckedFunction<? super F, ? extends Stream<? extends T>, ? extends E> function,
      Class<E> exceptionType) {
    Function<F, Maybe<Stream<? extends T>, E>> wrapped = maybe(function, exceptionType);
    return wrapped.andThen(Maybe::maybeStream);
  }

  /**
   * Wraps {@code function} to be used for a stream of Maybe.
   *
   * <p>Normally one should use {@link #maybe(CheckedBiFunction)} unless {@code E} is an unchecked
   * exception type.
   *
   * <p>For GWT code, wrap the function manually, as in:
   *
   * <pre>{@code
   *   private static <A, B, T> BiFunction<A, B, Maybe<T, FooException>> foo(
   *       CheckedBiFunction<A, B, T, FooException> function) {
   *     return (a, b) -> {
   *       try {
   *         return Maybe.of(function.apply(a, b));
   *       } catch (FooException e) {
   *         return Maybe.except(e);
   *       }
   *     };
   *   }
   * }</pre>
   */
  public static <A, B, T, E extends Throwable> BiFunction<A, B, Maybe<T, E>> maybe(
      CheckedBiFunction<? super A, ? super B, ? extends T, ? extends E> function,
      Class<E> exceptionType) {
    requireNonNull(function);
    requireNonNull(exceptionType);
    return (a, b) -> maybe(() -> function.apply(a, b), exceptionType);
  }

  /**
   * Wraps {@code function} that returns {@code Stream<T>} to one that returns
   * {@code Stream<Maybe<T, E>>} with exceptions of type {@code E} wrapped.
   */
  public static <A, B, T, E extends Throwable> BiFunction<A, B, Stream<Maybe<T, E>>> maybeStream(
      CheckedBiFunction<? super A, ? super B, ? extends Stream<? extends T>, ? extends E> function,
      Class<E> exceptionType) {
    BiFunction<A, B, Maybe<Stream<? extends T>, E>> wrapped = maybe(function, exceptionType);
    return wrapped.andThen(Maybe::maybeStream);
  }

  /**
   * Returns a wrapper of {@code stage} that if {@code stage} failed with exception of
   * {@code exceptionType}, that exception is caught and wrapped inside a {@link Maybe} to complete
   * the wrapper stage normally.
   *
   * <p>This is useful if the asynchronous code is interested in recovering from its own exception
   * without having to deal with other exception types.
   */
  public static <T, E extends Throwable> CompletionStage<Maybe<T, E>> catchException(
      Class<E> exceptionType, CompletionStage<? extends T> stage) {
    requireNonNull(exceptionType);
    CompletableFuture<Maybe<T, E>> future = new CompletableFuture<>();
    stage.handle((v, e) -> {
      try {
        if (e == null) {
          future.complete(Maybe.of(v));
        } else {
          unwrapFutureException(exceptionType, e)
              .map(cause -> future.complete(Maybe.except(cause)))
              .orElseGet(() -> future.completeExceptionally(e));
        }
      } catch (Throwable x) {  // Just in case there was a bug. Don't hang the thread.
        if (x != e) x.addSuppressed(e);
        future.completeExceptionally(x);
      }
      return null;
    });
    return future;
  }

  private static <E extends Throwable> Optional<E> unwrapFutureException(
      Class<E> causeType, Throwable exception) {
    for (Throwable e = exception; ; e = e.getCause()) {
      if (causeType.isInstance(e)) {
        return Optional.of(causeType.cast(e));
      }
      if (!(e instanceof ExecutionException || e instanceof CompletionException)) {
        return Optional.empty();
      }
    }
  }

  /** Adapts a {@code Maybe<Stream<T>, E>} to {@code Stream<Maybe<T, E>}. */
  private static <T, E extends Throwable> Stream<Maybe<T, E>> maybeStream(
      Maybe<? extends Stream<? extends T>, ? extends E> maybeStream) {
    return maybeStream.map(s -> s.map(Maybe::<T, E>of)).orElse(e -> Stream.of(except(e)));
  }

  /** No subclasses! */
  private Maybe() {}

  private static final class Success<T, E extends Throwable> extends Maybe<T, E> {
    private final T value;

    Success(T value) {
      this.value = value;
    }

    @Override public <T2> Maybe<T2, E> map(Function<? super T, ? extends T2> f) {
      return of(f.apply(value));
    }

    @Override public <T2> Maybe<T2, E> flatMap(Function<? super T, Maybe<T2, E>> f) {
      return f.apply(value);
    }

    @Override public boolean isPresent() {
      return true;
    }

    @Override public Maybe<T, E> ifPresent(Consumer<? super T> consumer) {
      consumer.accept(value);
      return this;
    }

    @Override public <X extends Throwable> T orElse(CheckedFunction<? super E, ? extends T, X> f)
        throws X {
      requireNonNull(f);
      return value;
    }

    @Override public String toString() {
      return String.valueOf(value);
    }

    @Override public int hashCode() {
      return value == null ? 0 : value.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Success<?, ?>) {
        Success<?, ?> that = (Success<?, ?>) obj;
        return Objects.equals(value, that.value);
      }
      return false;
    }
  }

  private static final class Failure<T, E extends Throwable> extends Maybe<T, E> {
    private final E exception;

    Failure(E exception) {
      this.exception = requireNonNull(exception);
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }

    @Override public <T2> Maybe<T2, E> map(Function<? super T, ? extends T2> f) {
      requireNonNull(f);
      return except(exception);
    }

    @Override public <T2> Maybe<T2, E> flatMap(Function<? super T, Maybe<T2, E>> f) {
      requireNonNull(f);
      return except(exception);
    }

    @Override public boolean isPresent() {
      return false;
    }

    @Override public Maybe<T, E> ifPresent(Consumer<? super T> consumer) {
      requireNonNull(consumer);
      return this;
    }

    @Override public <X extends Throwable> T orElse(CheckedFunction<? super E, ? extends T, X> f)
        throws X {
      return f.apply(exception);
    }

    @Override public String toString() {
      return "exception: " + exception;
    }

    @Override public int hashCode() {
      return exception.hashCode();
    }

    @Override public boolean equals(Object obj) {
      if (obj instanceof Failure<?, ?>) {
        Failure<?, ?> that = (Failure<?, ?>) obj;
        return exception.equals(that.exception);
      }
      return false;
    }
  }

  private static <E extends Throwable> E defaultWrapException(E exception) {
    if (exception instanceof InterruptedException) {
      Thread.interrupted();
      @SuppressWarnings("unchecked")  // Exceptions are always raw types.
      E interrupted = (E) new InterruptedException();
      return interrupted;
    }
    try {
      E wrapper = ExceptionBareboneSerializer.reserialize(exception);
      wrapper.fillInStackTrace();
      wrapper.initCause(exception);
      return wrapper;
    } catch (Exception e) {
      logger.log(Level.WARNING, "Cannot wrap " + exception.getClass(), e);
      return exception;
    }
  }

  /** Do not serialize cause, suppressed or stack trace. */
  private static final class ExceptionBareboneSerializer extends ObjectOutputStream {
    private final Throwable exception;

    private ExceptionBareboneSerializer(Throwable exception, OutputStream out) throws IOException {
      super(out);
      this.exception = exception;
      enableReplaceObject(true);
    }

    static <E extends Throwable> E reserialize(E exception)
        throws IOException, ClassNotFoundException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (ObjectOutputStream serializer = new ExceptionBareboneSerializer(exception, out)) {
        serializer.writeObject(exception);
      }
      ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(out.toByteArray()));
      @SuppressWarnings("unchecked")  // Exceptions are always raw types.
      E deserialized = (E) exception.getClass().cast(in.readObject());
      return deserialized;
    }

    @Override protected Object replaceObject(Object obj) throws IOException {
      if (obj == exception.getCause()) {
        return exception;
      }
      return replaceArrayOrList(obj);
    }

    private Object replaceArrayOrList(Object obj) throws IOException {
      if (obj instanceof Object[]) {
        Object[] arr = (Object[]) obj;
        if (Stream.of(arr).anyMatch(e -> shouldNotSerialize(e))) {
          return Array.newInstance(arr.getClass().getComponentType(), 0);
        }
      }
      if (obj instanceof List<?>) {
        if (((List<?>) obj).stream().anyMatch(e -> shouldNotSerialize(e))) {
          return Collections.emptyList();
        }
      }
      return super.replaceObject(obj);
    }

    private static boolean shouldNotSerialize(Object obj) {
      return obj instanceof StackTraceElement || obj instanceof Throwable;
    }
  }
}