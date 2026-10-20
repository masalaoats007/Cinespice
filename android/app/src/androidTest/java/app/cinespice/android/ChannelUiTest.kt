package app.cinespice.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChannelUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var telegram: Telegram
    private lateinit var originalStage: String
    private var originalLibrary: Library? = null
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var previousTheme: String? = null
    @Before fun setup() {
        telegram = (context.applicationContext as CinespiceApp).telegram
        originalStage = telegram.stage; originalLibrary = telegram.library
        previousTheme = context.getSharedPreferences("cinespice", 0).getString("cinespice.theme", "Ember")
        context.getSharedPreferences("cinespice", 0).edit().putString("cinespice.theme", "Ember").commit()
        context.deleteDatabase("library_-95.db"); context.deleteDatabase("saved_-95.db")
        telegram.stage = "ready"; telegram.library = Library(context, -95)
        telegram.library!!.chat(-101, "Cinema archive", true, 1); telegram.library!!.select(-101, true)
        telegram.library!!.chat(-102, "Documentary club", false, 2)
        val titles = listOf("The Quiet Horizon.2024.mkv", "After the Rain.2021.mp4", "A Place in the Sun.1951.mp4", "Ocean Stories.2023.mp4", "Midnight Train.2019.mkv", "The Long Goodbye.1973.mp4", "Parallel Worlds.2025.mkv", "One Summer.2020.mp4", "A Distant Light.2018.mp4", "City of Dreams.2022.mkv", "Northern Skies.2017.mp4", "The Last Frame.2026.mp4")
        titles.forEachIndexed { index, title ->
            val video = Video(Json.obj("chatId", -101, "messageId", index+1, "name", title, "duration", 5400, "size", 1073741824, "date", 1000 + index))
            telegram.library!!.save(video)
            if (index < 7) { telegram.library!!.progress(video, 120000L + index, 5400000, false); SystemClock.sleep(2) }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }
    @After fun cleanup() {
        scenario.close(); telegram.stage = originalStage; telegram.library = originalLibrary; telegram.codeDelivery = null
        context.deleteDatabase("library_-95.db"); context.deleteDatabase("saved_-95.db")
        context.getSharedPreferences("cinespice", 0).edit().putString("cinespice.theme", previousTheme).commit()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun favoritesWatchlistAndSelectionAreIndependent() {
        scenario.onActivity { activity ->
            val video = activity.model.videos.first()
            activity.model.toggle(video, "Favorites"); activity.model.toggle(video, "Watchlist")
            assertEquals(1, activity.model.favorites.size); assertEquals(1, activity.model.watchlist.size)
            activity.model.select(activity.model.channels.first { it.id() == -101L }); assertTrue(activity.model.videos.isEmpty())
            activity.model.select(activity.model.channels.first { it.id() == -101L }); assertEquals(12, activity.model.videos.size)
            assertEquals(1, activity.model.favorites.size); activity.model.navigate("favorites")
        }
        compose.onNodeWithText("Saved for later").assertIsDisplayed()
        compose.onNodeWithText("Watchlist", useUnmergedTree = true).performClick()
        scenario.recreate(); compose.onNodeWithText("Saved for later").assertIsDisplayed()
        screenshot("compose-favorites")
        scenario.onActivity { assertEquals("Watchlist", it.model.savedList); assertEquals(1, it.model.watchlist.size) }
    }
    @Test fun channelSearchKeepsFocusAndKeyboardDuringMetadataRefresh() {
        compose.onNodeWithContentDescription("Channels").performClick()
        compose.onNodeWithText("Search channels and groups").performClick().performTextInput("Cinema")
        scenario.onActivity { activity ->
            telegram.library!!.chat(-104, "Cinema discoveries", false, 4); activity.model.reload()
        }
        compose.onNodeWithText("Search channels and groups").assertIsFocused()
        compose.onNodeWithText("Cinema discoveries").assertIsDisplayed()
        compose.onNodeWithText("Documentary club").assertDoesNotExist()
        screenshot("compose-keyboard")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Home tab").assertIsDisplayed()
    }
    @Test fun emptySearchDraftRecreationNavigationAndPalette() {
        compose.onNodeWithContentDescription("Search tab").performClick()
        compose.onNodeWithText("Search your selected channels").assertIsDisplayed()
        compose.onNodeWithText("Search videos").performClick().performTextInput("Quiet")
        compose.onNodeWithText("Search videos").assertTextContains("Quiet")
        scenario.onActivity { assertEquals("Quiet", it.model.query) }
        scenario.recreate()
        compose.onNodeWithText("Search videos").assertTextContains("Quiet")
        compose.onNodeWithContentDescription("Clear search").performClick()
        scenario.onActivity { activity -> assertEquals("", activity.model.query); assertTrue(activity.model.searchResults.isEmpty()); assertEquals(5, activity.model.history.size) }
        compose.onNodeWithTag("library-grid").performScrollToIndex(1)
        compose.onNodeWithText("Search your selected channels").assertIsDisplayed()
        compose.onNodeWithContentDescription("Profile").performClick()
        scenario.onActivity { it.model.theme("Pearl") }
        compose.onNodeWithText("Your space").assertIsDisplayed(); screenshot("compose-pearl-profile")
        scenario.recreate(); scenario.onActivity { assertEquals("Pearl", it.model.palette); it.model.navigate("home") }
        compose.onNodeWithContentDescription("Hero 1 of 7: JUST ADDED").assertIsDisplayed(); screenshot("compose-pearl-home")
        scenario.onActivity { it.model.theme("Midnight") }; screenshot("compose-midnight-home")
        scenario.onActivity { it.model.theme("Sage") }; screenshot("compose-sage-home")
        scenario.onActivity { it.model.theme("Ember") }; screenshot("compose-ember-home")
        val roles = listOf("JUST ADDED", "TODAY'S PICK", "TRENDING FILMS", "POPULAR THIS WEEK", "DISCOVER SOMETHING NEW", "FROM THE CATALOGUE", "YOUR LAST SEARCH")
        for (index in 0..5) {
            compose.onNodeWithContentDescription("Hero ${index+1} of 7: ${roles[index]}").performTouchInput { swipeLeft() }
            compose.onNodeWithContentDescription("Hero ${index+2} of 7: ${roles[index+1]}").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("History tab").assertDoesNotExist()
        compose.onNodeWithContentDescription("Favorites and Watchlist").assertDoesNotExist()
        listOf("Home", "Search", "Favorites", "Downloads").forEach {
            compose.onNodeWithContentDescription("$it tab").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Favorites tab").performClick()
        compose.onNodeWithText("Saved for later").assertIsDisplayed()
        compose.onNodeWithContentDescription("Home tab").performClick()
        compose.onNodeWithContentDescription("Hero 1 of 7: JUST ADDED").assertIsDisplayed()
    }
    @Test fun phoneCodeAndCloudPasswordHaveSeparateForms() {
        scenario.onActivity { telegram.stage = "phone"; it.model.changed() }
        compose.onNodeWithText("Get code").assertIsDisplayed()
        compose.onNodeWithText("API ID").assertDoesNotExist()
        compose.onNodeWithContentDescription("Choose country").performClick()
        compose.onNodeWithText("Country or calling code").performTextInput("India")
        compose.onNodeWithText("India").performClick()
        compose.onAllNodesWithText("+91", substring = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Phone number").performTextInput("2025550100")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        scenario.onActivity {
            telegram.codeDelivery = TelegramLogin.CodeDelivery(Json.obj("phone_number", "+912025550100", "type", Json.obj("@type", "authenticationCodeTypeTelegramMessage"), "next_type", Json.obj("@type", "authenticationCodeTypeSms"), "timeout", 30), System.currentTimeMillis())
            telegram.stage = "code"; it.model.changed()
        }
        compose.onNodeWithText("Open Telegram on a device", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Resend code in", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Phone number").assertTextContains("2025550100")
        compose.onAllNodesWithText("+91", substring = true).onFirst().assertIsDisplayed()
        scenario.onActivity { telegram.stage = "code"; it.model.changed() }
        compose.onNodeWithText("Telegram login code").performTextInput("12345")
        compose.onNodeWithText("Submit code").assertIsEnabled()
        compose.onNodeWithText("Cloud password").assertDoesNotExist()
        scenario.onActivity { telegram.stage = "password"; it.model.changed() }
        compose.onNodeWithText("Cloud password").assertIsDisplayed()
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.onNodeWithText("Telegram login code").assertDoesNotExist()
    }

    @Test fun channelPaginationAndSortingAreIndependentOfHomeNavigation() {
        scenario.onActivity { activity ->
            repeat(61) { index -> telegram.library!!.save(Video(Json.obj("chatId", -101, "messageId", 100 + index, "name", "Page film %03d.mp4".format(index), "size", 1024, "date", 2000 + index))) }
            activity.model.reload()
            activity.model.browse(activity.model.channels.first { it.id() == -101L })
            activity.model.order(2)
            assertEquals(2, activity.model.channelOrder(-101))
            assertEquals(0, activity.model.channelOrder(-102))
        }
        compose.onNodeWithContentDescription("Home tab").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search tab").assertDoesNotExist()
        compose.onNodeWithContentDescription("Favorites tab").assertDoesNotExist()
        compose.onNodeWithContentDescription("Downloads tab").assertDoesNotExist()
        compose.onNodeWithTag("library-grid").performScrollToNode(hasText("3", substring = false))
        compose.onNodeWithText("3", substring = false).performClick()
        scenario.onActivity { assertEquals(2, it.model.collectionPageNumber) }
        scenario.recreate()
        scenario.onActivity { assertEquals(2, it.model.channelOrder(-101)) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Home tab").assertIsDisplayed()
    }

    @Test fun savedMembershipPersistsAcrossDatabaseReopenAndAccountsStayIsolated() {
        val video = Video(Json.obj("chatId", -101, "messageId", 1, "name", "Film.mp4"))
        val first = SavedStore.open(context, -96)
        first.entries().put(SavedStore.Member(SavedStore.id(video), "Favorites")); first.close()
        val reopened = SavedStore.open(context, -96); val second = SavedStore.open(context, -97)
        try { assertEquals(listOf("-101:1"), reopened.entries().members("Favorites")); assertTrue(second.entries().members("Favorites").isEmpty()) }
        finally { reopened.close(); second.close(); context.deleteDatabase("saved_-96.db"); context.deleteDatabase("saved_-97.db") }
    }
}
