package com.kuikagentbus.ui.rendering

import com.kuikagentbus.base.BridgeModule
import com.kuikagentbus.theme.DshTypography
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.reactive.ReactiveObserver
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.View
import com.tencent.kuiklybase.components.DefaultComponentsBridge
import com.tencent.kuiklybase.components.markdownComponents
import com.tencent.kuiklybase.KuiklyStreamingMarkdown
import com.tencent.kuiklybase.config.FontWeight
import com.tencent.kuiklybase.config.MarkdownColors
import com.tencent.kuiklybase.config.MarkdownConfig
import com.tencent.kuiklybase.config.MarkdownDimens
import com.tencent.kuiklybase.config.MarkdownTypography
import com.tencent.kuiklybase.config.TextStyleConfig
import com.tencent.kuiklybase.streaming.MarkdownBlock
import com.tencent.kuiklybase.streaming.MarkdownStreamingState
import com.tencent.kuikly.core.timer.setTimeout

/** 段间距相对字号的倍数。 */
private const val DSH_CHAT_BLOCK_RATIO = 1.2f

/** DSH theme wrapper around KuiklyMarkdown's streaming renderer. */
class DshMarkdownView : ComposeView<DshMarkdownAttr, ComposeEvent>() {
    private val streamingState = MarkdownStreamingState()
    private var blockList by observableList<MarkdownBlock>()
    private var blockCount by observable(0)
    private var treeEpoch by observable(0)
    private var liveKey by observable("")
    private var lastContent = ""
    private var lastStreaming = false
    private var pendingContent = ""
    private var pendingStreaming = false
    private var flushScheduled = false
    private var treeEpochDebounceScheduled = false
    /** 流式中 block 数量变化时，vfor 重挂载间隔（ms）。避免每个新段落都销毁重建所有 block view。 */
    private val TREE_EPOCH_DEBOUNCE_MS = 50

    override fun createAttr(): DshMarkdownAttr = DshMarkdownAttr()

    override fun createEvent(): ComposeEvent = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    if (ctx.attr.contentWidth > 0f) {
                        width(ctx.attr.contentWidth)
                    }
                }
                // vforLazy will not create later siblings of a nested vfor
                // after the row is already built (paragraph stays, code fence
                // never mounts). Remount when the block count changes so
                // KuiklyStreamingMarkdown can paint every block, including
                // the component's own codeFence.
                vbind({ ctx.treeEpoch }) {
                    vfor({ ctx.blockList }) { block ->
                        View {
                            attr {
                                if (ctx.attr.contentWidth > 0f) {
                                    width(ctx.attr.contentWidth)
                                }
                            }
                            vbind({
                                val live = block.blockIndex == ctx.blockCount - 1
                                if (live) ctx.liveKey else block.id
                            }) {
                                KuiklyStreamingMarkdown(
                                    state = ctx.streamingState,
                                    block = block,
                                    config = ctx.markdownConfig(),
                                    components = markdownComponents(
                                        codeFence = { model, container ->
                                            container.dshCodeFenceBlock(model) {
                                                val bridge = ctx.getPager().acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
                                                bridge.copyToPasteboard(it)
                                                bridge.toast("代码已复制")
                                            }
                                        },
                                        paragraph = { model, container ->
                                            DefaultComponentsBridge.paragraph(model, container)
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        ReactiveObserver.bindValueChange(this) {
            val live = attr.liveContent
            val content = live?.invoke() ?: attr.content
            val streaming = attr.streamingProvider?.invoke() ?: attr.streaming
            ReactiveObserver.addLazyTaskUtilEndCollectDependency {
                scheduleBlocksUpdate(content, streaming)
            }
        }
    }

    override fun viewWillUnload() {
        ReactiveObserver.unbindValueChange(this)
        super.viewWillUnload()
    }

    private fun scheduleBlocksUpdate(content: String, streaming: Boolean) {
        pendingContent = content
        pendingStreaming = streaming
        if (!streaming || lastContent.isEmpty() || lastContent == DshStreamingMarkdown.PLACEHOLDER) {
            flushBlocksUpdate()
            return
        }
        if (flushScheduled) return
        flushScheduled = true
        setTimeout(pagerId, DshStreamingMarkdown.FRAME_MS) {
            flushScheduled = false
            flushBlocksUpdate()
        }
    }

    private fun flushBlocksUpdate() {
        val content = pendingContent
        val streaming = pendingStreaming
        if (content == lastContent && streaming == lastStreaming) return
        val endingStream = lastStreaming && !streaming
        if (content.isEmpty() && lastContent.isNotEmpty()) {
            lastStreaming = streaming
            return
        }
        if (
            lastContent.isNotEmpty() &&
            content.length < lastContent.length &&
            lastContent.startsWith(content)
        ) {
            lastStreaming = streaming
            return
        }
        if (streaming && !lastStreaming) {
            streamingState.reset()
            blockList.clear()
            blockCount = 0
            liveKey = ""
            treeEpoch += 1
        }
        lastContent = content
        lastStreaming = streaming
        val input = if (content.isEmpty() && streaming) DshStreamingMarkdown.PLACEHOLDER else content
        val closed = if (streaming) DshStreamingMarkdown.closeOpenFence(input) else input
        val toParse = DshMath.transform(closed)
        val next = streamingState.update(toParse, force = !streaming) ?: return
        blockList.diffUpdate(next) { old, new -> old.id == new.id }
        val countChanged = blockCount != blockList.size
        if (countChanged) {
            blockCount = blockList.size
        }
        val newLiveKey = next.lastOrNull()?.id.orEmpty()
        if (liveKey != newLiveKey) {
            liveKey = newLiveKey
        }
        if (endingStream) {
            // 流式结束：立即重挂载一次，确保所有 block 都渲染到位。
            treeEpochDebounceScheduled = false
            treeEpoch += 1
        } else if (countChanged && !streaming) {
            // 非流式（force）：立即重挂载。
            treeEpoch += 1
        } else if (countChanged && streaming && !treeEpochDebounceScheduled) {
            // 流式中 block 数量变化：debounce 重挂载，避免每个新段落都销毁重建所有 block view。
            treeEpochDebounceScheduled = true
            setTimeout(pagerId, TREE_EPOCH_DEBOUNCE_MS) {
                treeEpochDebounceScheduled = false
                treeEpoch += 1
            }
        }
        flexNode.markDirty()
    }

    private fun markdownConfig(): MarkdownConfig {
        val dark = attr.darkMode
        val text = if (dark) 0xFFF5F6F7 else 0xFF1F1F23
        // 正文与用户气泡共用同一套语义字号；标题层级由 DshTypography 给出
        val type = attr.typography
        val body = type.body.size
        val line = type.body.lineHeight
        val code = type.code
        val label = type.label
        val h1 = type.heading(1)
        val h2 = type.heading(2)
        val h3 = type.heading(3)
        val h4 = type.heading(4)
        val h5 = type.heading(5)
        val block = body * DSH_CHAT_BLOCK_RATIO
        return MarkdownConfig(
            colors = MarkdownColors(
                text = text,
                codeBackground = if (dark) 0xFF242528 else 0xFFF9FAFB,
                inlineCodeBackground = if (dark) 0xFF34363A else 0xFFEBEEF2,
                dividerColor = if (dark) 0xFF45474B else 0xFFE5E5E5,
                tableBackground = if (dark) 0xFF202124 else 0xFFFAFAFA,
                blockQuoteBar = if (dark) 0xFF858990 else 0xFFA2A4A8,
                blockQuoteBackground = if (dark) 0xFF242528 else 0xFFF5F6F7,
                linkColor = if (dark) 0xFF78A4F8 else 0xFF4176E6,
                codeText = text,
            ),
            typography = MarkdownTypography(
                text = TextStyleConfig(fontSize = body, color = text, lineHeight = line),
                paragraph = TextStyleConfig(fontSize = body, color = text, lineHeight = line),
                code = TextStyleConfig(fontSize = code.size, fontFamily = "monospace", lineHeight = code.lineHeight),
                inlineCode = TextStyleConfig(fontSize = code.size, fontFamily = "monospace"),
                h1 = TextStyleConfig(fontSize = h1.size, fontWeight = FontWeight.Bold, color = text, lineHeight = h1.lineHeight),
                h2 = TextStyleConfig(fontSize = h2.size, fontWeight = FontWeight.Bold, color = text, lineHeight = h2.lineHeight),
                h3 = TextStyleConfig(fontSize = h3.size, fontWeight = FontWeight.SemiBold, color = text, lineHeight = h3.lineHeight),
                h4 = TextStyleConfig(fontSize = h4.size, fontWeight = FontWeight.SemiBold, color = text, lineHeight = h4.lineHeight),
                h5 = TextStyleConfig(fontSize = h5.size, fontWeight = FontWeight.SemiBold, color = text, lineHeight = h5.lineHeight),
                h6 = TextStyleConfig(fontSize = h5.size, fontWeight = FontWeight.SemiBold, color = text, lineHeight = h5.lineHeight),
                quote = TextStyleConfig(fontSize = body, color = if (dark) 0xFFB7BBC2 else 0xFF61666D, lineHeight = line * 0.95f),
                ordered = TextStyleConfig(fontSize = body, color = text, lineHeight = line),
                bullet = TextStyleConfig(fontSize = body, color = text, lineHeight = line),
                list = TextStyleConfig(fontSize = body, color = text, lineHeight = line),
                table = TextStyleConfig(fontSize = label.size, color = text, lineHeight = label.lineHeight),
                textLink = TextStyleConfig(fontSize = body, color = if (dark) 0xFF78A4F8 else 0xFF4176E6, lineHeight = line),
            ),
            dimens = MarkdownDimens(
                dividerThickness = 1f,
                codeBackgroundCornerSize = 8f,
                blockQuoteThickness = 3f,
                blockQuoteCornerSize = 6f,
                tableCellWidth = 136f,
                tableCellPadding = 10f,
                tableCornerSize = 8f,
            ),
            codeHighlightDarkTheme = dark,
            codeHighlightEnabled = true,
            padding = com.tencent.kuiklybase.config.MarkdownPadding(
                // 段间距、列表间距等均按正文基准字号的百分比推导
                block = block,
                list = body * 0.5f,
                listItemTop = 0f,
                listItemBottom = 0f,
                listIndent = body * 1.2f,
                codeBlock = 8f,
                blockQuotePaddingLeft = block,
                blockQuoteBarPaddingLeft = body * 0.25f,
                blockQuoteTextVertical = body * 0.5f,
            ),
        )
    }
}

class DshMarkdownAttr : ComposeAttr() {
    var contentWidth: Float by observable(0f)
    var typography: DshTypography by observable(DshTypography.Default)
    var content: String by observable("")
    var streaming: Boolean by observable(false)
    var darkMode: Boolean by observable(false)
    var liveContent: (() -> String)? = null
    var streamingProvider: (() -> Boolean)? = null
}

fun ViewContainer<*, *>.DshMarkdown(init: DshMarkdownView.() -> Unit) {
    addChild(DshMarkdownView(), init)
}
