// ERROR: Property must be initialized or be abstract
import A.Nested

internal class A @JvmOverloads constructor(nested: Nested = Nested(Nested.FIELD)) {

    internal class Nested(p: Int) {
        companion object {

            val FIELD = 0
        }
    }
}

internal class B {
    var nested: Nested
}