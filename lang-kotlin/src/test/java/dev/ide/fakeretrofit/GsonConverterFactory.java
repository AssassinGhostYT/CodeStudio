package dev.ide.fakeretrofit;

/** Stands in for GsonConverterFactory: a Java class with a static create() factory returning itself. */
public final class GsonConverterFactory implements Factory {
    public static GsonConverterFactory create() { return new GsonConverterFactory(); }

    /** A void static method: importing it and calling it bare must resolve (its return type is Unit). */
    public static void assertEquals(int expected, int actual) { }
}
