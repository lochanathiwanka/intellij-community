// "Mark 'foo' as safe" "true"
package org.checkerframework.checker.tainting.qual;

class Simple {

    void simple() {
      sink(foo());
    }

    @Untainted String foo() {
        return "foo";
    }

    void sink(@Untainted String s1) {}

}