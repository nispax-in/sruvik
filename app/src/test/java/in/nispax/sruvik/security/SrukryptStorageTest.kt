package `in`.nispax.sruvik.security

import org.junit.Assert.assertEquals
import org.junit.Test

class SrukryptStorageTest {
    @Test
    fun acceptsPrimaryAndRepairedKeystoreAliases() {
        assertEquals(
            "in.nispax.sruvik.srukrypt.master",
            SrukryptStorage.keyAlias("master")
        )
        assertEquals(
            "in.nispax.sruvik.srukrypt.master.repaired",
            SrukryptStorage.keyAlias("master.repaired")
        )
    }

    @Test
    fun rejectsPathSeparatorsAndUnsupportedCharacters() {
        listOf("../master", "master/repaired", "master repaired", "").forEach { value ->
            try {
                SrukryptStorage.keyAlias(value)
                throw AssertionError("Expected an invalid alias ID to be rejected: $value")
            } catch (expected: IllegalArgumentException) {
                // Expected: alias IDs remain a bounded, path-independent allowlist.
            }
        }
    }
}
