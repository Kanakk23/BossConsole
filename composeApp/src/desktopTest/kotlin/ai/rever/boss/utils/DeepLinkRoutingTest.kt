package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards deep-link dispatch: hosts match exactly (so a longer host is never
 * swallowed by a shorter one), the hosts that act on a window at dispatch take
 * the single resolved id, and anything unrouted still reaches the default flow.
 */
class DeepLinkRoutingTest {
    @Test
    fun `each known host routes to its own handler`() {
        assertEquals(DeepLinkHost.URL, routedDeepLinkHost("boss://url?url=https%3A%2F%2Fexample.com"))
        assertEquals(DeepLinkHost.WORKSPACE, routedDeepLinkHost("boss://workspace?path=/tmp/ws.json"))
        assertEquals(DeepLinkHost.FILE, routedDeepLinkHost("boss://file?path=/tmp/a.kt"))
        assertEquals(DeepLinkHost.TERMINAL, routedDeepLinkHost("boss://terminal"))
        assertEquals(DeepLinkHost.FOLDER, routedDeepLinkHost("boss://folder?path=/tmp/project"))
        assertEquals(DeepLinkHost.PLUGIN, routedDeepLinkHost("boss://plugin?id=bookmarks"))
        assertEquals(DeepLinkHost.SPLIT, routedDeepLinkHost("boss://split?orientation=horizontal"))
        assertEquals(DeepLinkHost.MCP, routedDeepLinkHost("boss://mcp?tool=codebase_read"))
    }

    @Test
    fun `a longer host is never swallowed by a shorter one`() {
        // Prefix dispatch used to route these into plugin, file, url, terminal,
        // folder, workspace and split respectively, mis-parsing them instead of
        // leaving them to the default flow.
        assertNull(routedDeepLinkHost("boss://plugins"))
        assertNull(routedDeepLinkHost("boss://plugins?id=bookmarks"))
        assertNull(routedDeepLinkHost("boss://filesystem/list?path=/tmp"))
        assertNull(routedDeepLinkHost("boss://urlencode?url=x"))
        assertNull(routedDeepLinkHost("boss://terminals"))
        assertNull(routedDeepLinkHost("boss://folders?path=/tmp"))
        assertNull(routedDeepLinkHost("boss://workspaces"))
        assertNull(routedDeepLinkHost("boss://splitscreen"))
    }

    @Test
    fun `unrouted links fall through to the default flow`() {
        assertNull(routedDeepLinkHost("boss://auth/verify#access_token=abc"))
        assertNull(routedDeepLinkHost("boss://"))
        assertNull(routedDeepLinkHost("boss://?path=/tmp"))
        assertNull(routedDeepLinkHost("https://example.com/plugin"))
        assertNull(routedDeepLinkHost("bossx://plugin?id=bookmarks"))
        assertNull(routedDeepLinkHost(""))
    }

    @Test
    fun `host parsing stops at the first delimiter and ignores case`() {
        assertEquals("plugin", deepLinkHostOf("boss://plugin?id=bookmarks"))
        assertEquals("plugin", deepLinkHostOf("boss://plugin/open?id=bookmarks"))
        assertEquals("plugin", deepLinkHostOf("boss://plugin#fragment"))
        assertEquals("plugin", deepLinkHostOf("boss://plugin"))
        assertEquals("plugin", deepLinkHostOf("BOSS://Plugin?id=bookmarks"))
        assertEquals(DeepLinkHost.PLUGIN, routedDeepLinkHost("BOSS://PLUGIN?id=bookmarks"))
        assertNull(deepLinkHostOf("boss:/plugin"))
    }

    @Test
    fun `hosts that act on a window at dispatch get the resolved id, including null`() {
        // boss://plugin and boss://folder used to read focusedWindowFlow directly
        // and drop the link for MCP/CLI callers, while boss://split resolved a
        // usable window. All three now take the id this single resolution
        // produces — and must keep taking it when it resolves to null, which is
        // the branch that logs instead of acting.
        listOf(DeepLinkHost.FOLDER, DeepLinkHost.PLUGIN, DeepLinkHost.SPLIT, DeepLinkHost.MCP).forEach { host ->
            var calls = 0
            assertEquals(
                "window-a",
                targetWindowIdFor(host) {
                    calls++
                    "window-a"
                },
            )
            assertEquals(1, calls, "${host.host} must resolve a window at dispatch")
            assertNull(targetWindowIdFor(host) { null })
        }
    }

    @Test
    fun `hosts that queue a CLI command do not resolve a window at dispatch`() {
        // These resolve downstream instead (URLHandlerService / CLICommandHandler),
        // through the same lookup, because a dispatch-time id would be stale by
        // the time a queued command drains after a cold start.
        listOf(DeepLinkHost.URL, DeepLinkHost.WORKSPACE, DeepLinkHost.FILE, DeepLinkHost.TERMINAL).forEach { host ->
            var calls = 0
            assertNull(
                targetWindowIdFor(host) {
                    calls++
                    "window-a"
                },
            )
            assertEquals(0, calls, "${host.host} must not resolve a window at dispatch")
        }
    }

    @Test
    fun `unrouted links reach the flow that auth and other handlers collect`() {
        // The fall-through is the branch most likely to regress, and it is the
        // one path with no coroutine, window or event bus behind it.
        DeepLinkHandler.clearDeepLink()
        DeepLinkHandler.processDeepLink("boss://auth/verify#access_token=abc")
        assertEquals("boss://auth/verify#access_token=abc", DeepLinkHandler.deepLinkFlow.value)

        // A host that prefix dispatch would have mis-parsed as boss://plugin now
        // lands here instead of opening a panel named "plugins".
        DeepLinkHandler.clearDeepLink()
        DeepLinkHandler.processDeepLink("boss://plugins?id=bookmarks")
        assertEquals("boss://plugins?id=bookmarks", DeepLinkHandler.deepLinkFlow.value)

        DeepLinkHandler.clearDeepLink()
        assertNull(DeepLinkHandler.deepLinkFlow.value)
    }

    @Test
    fun `host names are unique and lower case so exact matching stays total`() {
        val hosts = DeepLinkHost.entries.map { it.host }
        assertEquals(hosts.toSet().size, hosts.size)
        assertEquals(hosts, hosts.map { it.lowercase() })
        hosts.forEach { host ->
            assertEquals(DeepLinkHost.entries.first { it.host == host }, routedDeepLinkHost("boss://$host"))
        }
    }
}
