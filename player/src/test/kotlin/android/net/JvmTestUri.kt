package android.net

import android.os.Parcel

/**
 * The one [Uri] a plain JVM unit test can hold.
 *
 * AGP's mockable `android.jar` throws "not mocked" on every `Uri` method, so
 * `Uri.parse` cannot run in `:player`'s unit tests, and `Uri`'s own constructor
 * is package-private -- which a class sitting in `android.net` (as this one
 * deliberately does) can reach. This file lives in test sources and ships
 * nowhere.
 *
 * Holding one is all `mediaItem` (see `dev.baton.player.BatonPlayer.kt`) ever
 * asks of it: Media3 1.4.1's build path -- `MediaItem.Builder.build`, the
 * `SubtitleConfiguration` copy constructor it goes through, and
 * `MediaItem$Subtitle` -- stores and copies the reference without calling a
 * single method on it. Every override below therefore fails loudly instead of
 * pretending: if a Media3 upgrade ever makes that claim false, the test that
 * uses this will say so, not pass by accident.
 */
class JvmTestUri(private val text: String) : Uri() {

    override fun equals(other: Any?): Boolean = other is JvmTestUri && other.text == text

    override fun hashCode(): Int = text.hashCode()

    /** The only method anything may reasonably print; also never called by the build path. */
    override fun toString(): String = text

    override fun isHierarchical(): Boolean = unreachable()

    override fun isRelative(): Boolean = unreachable()

    override fun getScheme(): String? = unreachable()

    override fun getSchemeSpecificPart(): String? = unreachable()

    override fun getEncodedSchemeSpecificPart(): String? = unreachable()

    override fun getAuthority(): String? = unreachable()

    override fun getEncodedAuthority(): String? = unreachable()

    override fun getUserInfo(): String? = unreachable()

    override fun getEncodedUserInfo(): String? = unreachable()

    override fun getHost(): String? = unreachable()

    override fun getPort(): Int = unreachable()

    override fun getPath(): String? = unreachable()

    override fun getEncodedPath(): String? = unreachable()

    override fun getQuery(): String? = unreachable()

    override fun getEncodedQuery(): String? = unreachable()

    override fun getFragment(): String? = unreachable()

    override fun getEncodedFragment(): String? = unreachable()

    override fun getPathSegments(): List<String> = unreachable()

    override fun getLastPathSegment(): String? = unreachable()

    override fun buildUpon(): Builder = unreachable()

    override fun describeContents(): Int = unreachable()

    override fun writeToParcel(parcel: Parcel, flags: Int): Unit = unreachable()

    private fun unreachable(): Nothing =
        error("JvmTestUri is a placeholder -- '$text' -- and this method is never called on Media3's MediaItem build path")
}
