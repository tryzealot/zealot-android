package im.ews.zealot

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class UpdatePromptsTest {
    private data class Reply(val status: Int, val body: String)

    private val replies = LinkedBlockingQueue<Reply>()
    private val started = LinkedBlockingQueue<Unit>()
    private val finished = LinkedBlockingQueue<Unit>()
    private val requests = AtomicInteger()
    private val main = Shadows.shadowOf(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests.incrementAndGet()
        started.add(Unit)
        val reply = checkNotNull(replies.poll(5, TimeUnit.SECONDS))
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(reply.status)
            .message(if (reply.status == 200) "OK" else "Error")
            .body(reply.body.toResponseBody())
            .build()
    }.build().also { it.dispatcher.idleCallback = Runnable { finished.add(Unit) } }

    @Test
    fun laterLastsForTheSessionAndManualChecksCanShowTheSameRelease() {
        release("12")
        val first = screen()
        val firstClient = zealot(first)
        val prompts = firstClient.enableUpdatePrompts()
        assertSame(prompts, firstClient.enableUpdatePrompts())
        assertEquals(0, requests.get())
        first.show()
        deliver()
        first.tap(R.string.zealot_update_later)
        settleMotion()
        assertNull(first.card())
        first.pause().stop().destroy()

        release("12")
        val second = screen()
        val secondPrompts = zealot(second).enableUpdatePrompts()
        second.show()
        deliver()
        assertNull(second.card())

        release("12")
        secondPrompts.checkNow()
        secondPrompts.checkNow()
        second.assertText(second.get().getString(R.string.zealot_prompt_checking))
        deliver()
        second.assertText(second.get().getString(R.string.zealot_prompt_available))
        assertEquals(3, requests.get())
        second.pause().stop().destroy()
    }

    @Test
    fun laterAppliesOnlyToTheSelectedChannelAndBuild() {
        release("12")
        val first = screen()
        zealot(first).enableUpdatePrompts()
        first.show()
        deliver()
        first.tap(R.string.zealot_update_later)
        first.pause().stop().destroy()

        release("12")
        val otherChannel = screen()
        zealot(otherChannel).setChannelKey("other").enableUpdatePrompts()
        otherChannel.show()
        deliver()
        assertNotNull(otherChannel.card())
        otherChannel.pause().stop().destroy()

        release("13")
        val nextBuild = screen()
        zealot(nextBuild).enableUpdatePrompts()
        nextBuild.show()
        deliver()
        assertNotNull(nextBuild.card())
        nextBuild.tap(R.string.zealot_update_later)
        nextBuild.pause().stop().destroy()

        release("12")
        val earlierBuild = screen()
        zealot(earlierBuild).enableUpdatePrompts()
        earlierBuild.show()
        deliver()
        assertNull(earlierBuild.card())
        earlierBuild.pause().stop().destroy()
    }

    @Test
    fun manualErrorsOfferRetryAndOpeningAnUpdateRechecksOnReturn() {
        replies.add(Reply(503, "{}"))
        val screen = screen()
        val prompts = zealot(screen).enableUpdatePrompts()
        screen.show()
        deliver()
        assertNull(screen.card())

        replies.add(Reply(503, "{}"))
        prompts.checkNow()
        deliver()
        screen.assertText(screen.get().getString(R.string.zealot_prompt_check_failed))

        release("12")
        screen.tap(R.string.zealot_prompt_retry)
        deliver()
        screen.tap(R.string.zealot_prompt_open)
        val intent = Shadows.shadowOf(screen.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.com/install", intent.data.toString())
        settleMotion()
        assertNull(screen.card())

        release("12")
        screen.pause().resume().windowFocusChanged(true)
        deliver()
        assertNull(screen.card())
        screen.pause().stop().destroy()
    }

    @Test
    fun pausingCancelsAnInFlightCheckAndClosingStopsFutureChecks() {
        val screen = screen()
        val prompts = zealot(screen).enableUpdatePrompts()
        screen.show()
        assertNotNull(started.poll(5, TimeUnit.SECONDS))
        screen.pause()
        release("12")
        deliver()
        assertNull(screen.card())

        replies.add(Reply(200, """{"releases":[]}"""))
        screen.resume().windowFocusChanged(true)
        deliver()
        assertNull(screen.card())

        prompts.close()
        prompts.checkNow()
        assertEquals(2, requests.get())
        screen.pause().stop().destroy()
    }

    @Test
    fun releaseNotesExpandInsideTheCardWithoutAnotherCheck() {
        val changelog = "Saved filters now sync between devices."
        release("12", changelog)
        val screen = screen()
        zealot(screen).enableUpdatePrompts()
        screen.show()
        deliver()
        val card = requireNotNull(screen.card())
        val notes = card.descendants().filterIsInstance<TextView>().single { it.text == changelog }
        assertFalse(notes.isShown)

        screen.tap(R.string.zealot_prompt_notes)
        assertTrue(notes.isShown)
        assertSame(card, screen.card())
        screen.tap(R.string.zealot_prompt_hide_notes)
        screen.tap(R.string.zealot_prompt_notes)
        settleMotion()
        assertTrue(notes.isShown)
        screen.tap(R.string.zealot_prompt_hide_notes)
        settleMotion()
        assertFalse(notes.isShown)
        assertEquals(1, requests.get())

        screen.tap(R.string.zealot_update_later)
        settleMotion()
        assertNull(screen.card())
        screen.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun promptsUseTheirOwnLocaleForActionsNotesAndErrors() {
        val screen = screen()
        val changelog = "Saved filters now sync."
        release("12", changelog)
        val zealot = zealot(screen).setLocale(Locale.FRANCE)
        val prompts = zealot.enableUpdatePrompts()
        zealot.setLocale(null)
        screen.show()
        deliver()
        screen.assertText("Mise à jour disponible")
        screen.assertText("Plus tard")
        screen.tap("Nouveautés")
        settleMotion()
        screen.assertText("Masquer les nouveautés")
        screen.assertText(changelog)
        assertEquals("Später", screen.get().getString(R.string.zealot_update_later))

        replies.add(Reply(503, "{}"))
        prompts.checkNow()
        screen.assertText("Recherche de mises à jour…")
        deliver()
        screen.assertText("Impossible de rechercher des mises à jour")
        screen.assertText("Réessayer")
        screen.assertText("Fermer")
        screen.pause().stop().destroy()
    }

    private fun screen(): ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create().also {
        it.get().setContentView(FrameLayout(it.get()))
    }

    private fun ActivityController<Activity>.show() = start().resume().visible().windowFocusChanged(true)

    private fun zealot(screen: ActivityController<Activity>) = Zealot.create(screen.get())
        .setEndpoint("https://example.com")
        .setChannelKey("default")
        .setHttpClient(client)

    private fun release(build: String, changelog: String = "") {
        replies.add(Reply(200, """{"releases":[{"release_version":"2.0","build_version":"$build",
            "install_url":"https://example.com/install","text_changelog":"$changelog"}]}"""))
    }

    private fun deliver() {
        assertNotNull(finished.poll(5, TimeUnit.SECONDS))
        main.idle()
    }

    private fun settleMotion() {
        main.idleFor(350, TimeUnit.MILLISECONDS)
    }

    private fun ActivityController<Activity>.card(): View? = get().findViewById(R.id.zealot_update_banner)

    private fun ActivityController<Activity>.tap(label: Int) = tap(get().getString(label))

    private fun ActivityController<Activity>.tap(text: String) {
        val button = requireNotNull(card()).descendants().filterIsInstance<Button>().single { it.text == text }
        assertTrue(button.performClick())
    }

    private fun ActivityController<Activity>.assertText(text: String) {
        assertTrue(requireNotNull(card()).descendants().filterIsInstance<TextView>().any { it.text == text })
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }
}
