package io.github.rwml.android;

import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Extensions resolve parent-first, so the APK must provide the current coroutine ABI. */
final class HostCoroutineCompatibilityTest {
    @Test void kotlin24ExtensionsCanResolveDefaultRunBlockingEntryPoint() throws Exception {
        assertNotNull(BuildersKt.class.getMethod("runBlockingK$default",
            CoroutineContext.class, Function2.class, int.class, Object.class));
    }

    @Test void activityViewModelOwnerExportsItsCurrentFactoryApi() throws Exception {
        assertNotNull(androidx.lifecycle.ViewModelProvider.class.getField("Companion"));
    }
    @Test void sharedPrimitiveCollectionsExposeDefaultBinarySearchApi() throws Exception {
        Class<?> type = Class.forName("androidx.collection.IntList");
        assertNotNull(type.getMethod("binarySearch$default", type,
            int.class, int.class, int.class, int.class, Object.class));
    }
    @Test void serializationProvidesNativeDefaultMethodsForModernExtensionEncoders() throws Exception {
        assertTrue(kotlinx.serialization.encoding.CompositeEncoder.class
            .getMethod("shouldEncodeElementDefault", kotlinx.serialization.descriptors.SerialDescriptor.class, int.class)
            .isDefault());
    }
}
