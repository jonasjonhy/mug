package org.mu.util;

import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mu.function.CheckedBiFunction;
import org.mu.function.CheckedFunction;
import org.mu.function.CheckedSupplier;

import com.google.common.truth.IterableSubject;

@RunWith(JUnit4.class)
public class MaybeTest {

  @Test public void testGet_success() throws Throwable {
    assertThat(Maybe.of("test").get()).isEqualTo("test");
  }

  @Test public void testGet_failure() throws Throwable {
    MyException exception = new MyException("test");
    Maybe<?, MyException> maybe = Maybe.except(exception);
    assertSame(exception, assertThrows(MyException.class, maybe::get));
  }

  @Test public void testMap_success() {
    Maybe<Integer, MyException> maybe = Maybe.of(1);
    assertThat(maybe.map(Object::toString)).isEqualTo(Maybe.of("1"));
    assertThrows(NullPointerException.class, () -> maybe.map(null));
  }

  @Test public void testMap_failure() {
    MyException exception = new MyException("test");
    Maybe<?, MyException> maybe = Maybe.except(exception).map(Object::toString);
    assertSame(exception, assertThrows(MyException.class, maybe::get));
    assertThrows(NullPointerException.class, () -> maybe.map(null));
  }

  @Test public void testFlatMap_success() {
    Maybe<Integer, MyException> maybe = Maybe.of(1);
    assertThat(maybe.flatMap(o -> Maybe.of(o.toString()))).isEqualTo(Maybe.of("1"));
    assertThrows(NullPointerException.class, () -> maybe.flatMap(null));
  }

  @Test public void testFlatMap_failure() {
    MyException exception = new MyException("test");
    Maybe<?, MyException> maybe = Maybe.except(exception).flatMap(o -> Maybe.of(o.toString()));
    assertSame(exception, assertThrows(MyException.class, maybe::get));
    assertThrows(NullPointerException.class, () -> maybe.flatMap(null));
  }

  @Test public void testIsPresent() {
    assertThat(Maybe.of(1).isPresent()).isTrue();
    assertThat(Maybe.except(new Exception()).isPresent()).isFalse();
  }

  @Test public void testIfPresent_success() {
    AtomicInteger succeeded = new AtomicInteger();
    Maybe.of(100).ifPresent(i -> succeeded.set(i));
    assertThat(succeeded.get()).isEqualTo(100);
    assertThrows(NullPointerException.class, () -> Maybe.of(0).ifPresent(null));
  }

  @Test public void testIfPresent_failure() {
    AtomicBoolean succeeded = new AtomicBoolean();
    Maybe.except(new Exception()).ifPresent(i -> succeeded.set(true));
    assertThat(succeeded.get()).isFalse();
    assertThrows(NullPointerException.class, () -> Maybe.except(new Exception()).ifPresent(null));
  }

  @Test public void testOrElse() {
    assertThat(Maybe.of("good").orElse(Throwable::getMessage)).isEqualTo("good");
    assertThat(Maybe.except(new Exception("bad")).orElse(Throwable::getMessage)).isEqualTo("bad");
    assertThrows(NullPointerException.class, () -> Maybe.of("good").orElse(null));
    assertThrows(NullPointerException.class, () -> Maybe.except(new Exception()).orElse(null));
  }

  @Test public void testCatching_success() {
    AtomicReference<Throwable> failed = new AtomicReference<>();
    Maybe.of(100).catching(e -> {failed.set(e);});
    assertThat(failed.get()).isNull();
    assertThrows(NullPointerException.class, () -> Maybe.of(0).catching(null));
  }

  @Test public void testCatching_failure() {
    MyException exception = new MyException("test");
    AtomicReference<Throwable> failed = new AtomicReference<>();
    Maybe.except(exception).catching(e -> {failed.set(e);});
    assertThat(failed.get()).isSameAs(exception);
    assertThrows(NullPointerException.class, () -> Maybe.except(exception).catching(null));
  }

  @Test public void testEqualsAndHashCode() {
    Maybe<?, ?> fail1 = Maybe.except(new MyException("bad"));
    Maybe<?, ?> fail2 = Maybe.except(new Exception());
    assertThat(Maybe.of("hello")).isEqualTo(Maybe.of("hello"));
    assertThat(Maybe.of("hello").hashCode()).isEqualTo(Maybe.of("hello").hashCode());
    assertThat(Maybe.of("hello")).isNotEqualTo(Maybe.of("world"));
    assertThat(Maybe.of("hello")).isNotEqualTo(fail1);
    assertThat(Maybe.of("hello")).isNotEqualTo(null);
    assertThat(fail1).isEqualTo(fail1);
    assertThat(fail1.hashCode()).isEqualTo(fail1.hashCode());
    assertThat(fail1).isNotEqualTo(fail2);
    assertThat(fail1).isNotEqualTo(Maybe.of("hello"));
    assertThat(fail1).isNotEqualTo(null);
  }

  @Test public void testNulls() {
    assertThrows(NullPointerException.class, () -> Maybe.of(null));
    assertThrows(NullPointerException.class, () -> Maybe.except(null));
    assertThrows(NullPointerException.class, () -> Maybe.wrap((CheckedSupplier<?, ?>) null));
    assertThrows(
        NullPointerException.class,
        () -> Maybe.wrap((CheckedSupplier<?, RuntimeException>) null, RuntimeException.class));
    assertThrows(
        NullPointerException.class, () -> Maybe.wrap(() -> justReturn("good"), null));
    assertThrows(NullPointerException.class, () -> Maybe.wrap((CheckedFunction<?, ?, ?>) null));
    assertThrows(
        NullPointerException.class,
        () -> Maybe.wrap((CheckedFunction<?, ?, RuntimeException>) null, RuntimeException.class));
    assertThrows(NullPointerException.class, () -> Maybe.wrap(this::justReturn, null));
    assertThrows(
        NullPointerException.class, () -> Maybe.wrap((CheckedBiFunction<?, ?, ?, ?>) null));
    assertThrows(
        NullPointerException.class,
        () -> Maybe.wrap((String a, String b) -> justReturn(a + b), null));
    assertThrows(
        NullPointerException.class,
        () -> Maybe.wrap((CheckedBiFunction<?, ?, ?, Exception>) null, Exception.class));
    assertThrows(NullPointerException.class, () -> Maybe.byValue(null));
  }

  @Test public void testStream_success() throws MyException {
    assertStream(Stream.of("hello", "friend").map(Maybe.wrap(this::justReturn)))
        .containsExactly("hello", "friend").inOrder();
  }

  @Test public void testStream_exception() {
    Stream<Maybe<String, MyException>> stream = 
        Stream.of("hello", "friend").map(Maybe.wrap(this::raise));
    assertThrows(MyException.class, () -> Maybe.collect(stream));
  }

  @Test public void testStream_uncheckedExceptionNotCaptured() {
    Stream<String> stream = Stream.of("hello", "friend")
          .map(Maybe.wrap(this::raiseUnchecked))
          .flatMap(m -> m.catching(e -> {}));
    assertThrows(RuntimeException.class, () -> stream.collect(toList()));
  }

  @Test public void testStream_swallowException() {
    assertThat(Stream.of("hello", "friend")
            .map(Maybe.wrap(this::raise))
            .flatMap(m -> m.catching(e -> {}))
            .collect(toList()))
        .isEmpty();
  }

  @Test public void testStream_generateSuccess() {
    assertThat(Stream.generate(Maybe.wrap(() -> justReturn("good"))).findFirst().get())
        .isEqualTo(Maybe.of("good"));
  }

  @Test public void testStream_generateFailure() {
    Maybe<String, MyException> maybe =
        Stream.generate(Maybe.wrap(() -> raise("bad"))).findFirst().get();
    assertThat(assertThrows(MyException.class, maybe::get).getMessage()).isEqualTo("bad");
  }

  @Test public void testFilterByValue_successValueFiltered() throws MyException {
    assertStream(Stream.of("hello", "friend")
            .map(Maybe.wrap(this::justReturn))
            .filter(Maybe.byValue("hello"::equals)))
        .containsExactly("hello");
  }

  @Test public void testFilterByValue_failuresNotFiltered() {
    List<Maybe<String, MyException>> maybes = Stream.of("hello", "friend")
        .map(Maybe.wrap(this::raise))
        .filter(Maybe.byValue(s -> false))
        .collect(toList());
    assertThat(maybes).hasSize(2);
    assertThat(assertThrows(MyException.class, () -> maybes.get(0).get()).getMessage())
        .isEqualTo("hello");
    assertThat(assertThrows(MyException.class, () -> maybes.get(1).get()).getMessage())
        .isEqualTo("friend");
  }

  private String raise(String s) throws MyException {
    throw new MyException(s);
  }

  @SuppressWarnings("unused")  // Signature needed for Maybe.wrap()
  private String raiseUnchecked(String s) throws MyException {
    throw new RuntimeException(s);
  }

  @SuppressWarnings("unused")  // Signature needed for Maybe.wrap()
  private String justReturn(String s) throws MyException {
    return s;
  }

  private static <T, E extends Throwable> IterableSubject assertStream(
      Stream<Maybe<T, E>> stream) throws E {
    return assertThat(Maybe.collect(stream));
  }

  @SuppressWarnings("serial")
  private static class MyException extends Exception {
    MyException(String message) {
      super(message);
    }
  }
}