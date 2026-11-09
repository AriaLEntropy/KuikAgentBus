package com.kuikagentbus.ui.rendering

import com.kuikagentbus.theme.DshColorTokens
import com.kuikagentbus.theme.DshDefaultTheme
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 单行文本输入弹窗：新建项目这类“先填一个名字再继续”的场景。
 * 结构沿用 DshSessionConfirmDialog，输入框放在说明与按钮之间。
 */
internal fun ViewContainer<*, *>.DshTextInputDialog(
    visible: () -> Boolean,
    title: String,
    hint: String,
    value: () -> String,
    error: () -> String,
    busy: () -> Boolean,
    confirmLabel: String,
    confirmColor: () -> Color,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    pageViewWidth: Float,
    verticalOffset: Float = 0f,
    colors: () -> DshColorTokens = { DshDefaultTheme.light },
) {
    vif({ visible() }) {
        Modal(inWindow = true) {
            attr {
                absolutePositionAllZero()
                alignItemsCenter()
                justifyContentCenter()
                paddingLeft(20f)
                paddingRight(20f)
                backgroundColor(Color(0x66000000))
            }
            View {
                attr {
                    width(pageViewWidth - 40f)
                    maxWidth(420f)
                    marginTop(verticalOffset)
                    borderRadius(16f)
                    backgroundColor(colors().bgLayer1)
                }
                Text {
                    attr {
                        text(title)
                        marginTop(22f)
                        alignSelfCenter()
                        fontSize(18f)
                        fontWeightBold()
                        color(colors().labelPrimary)
                    }
                }
                View {
                    attr {
                        height(44f)
                        marginTop(14f)
                        marginLeft(20f)
                        marginRight(20f)
                        borderRadius(10f)
                        backgroundColor(colors().bgLayer2)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(12f)
                        paddingRight(12f)
                    }
                    Input {
                        attr {
                            flex(1f)
                            fontSize(15f)
                            color(colors().labelPrimary)
                            placeholder(hint)
                            placeholderColor(colors().labelTertiary)
                            text(value())
                        }
                        event { textDidChange { onValueChange(it.text) } }
                    }
                }
                vif({ error().isNotEmpty() }) {
                    Text {
                        attr {
                            text(error())
                            marginTop(8f)
                            marginLeft(20f)
                            marginRight(20f)
                            fontSize(12f)
                            color(colors().stateErrorPrimary)
                        }
                    }
                }
                View {
                    attr {
                        marginTop(18f)
                        height(1f)
                        backgroundColor(colors().borderL1)
                    }
                }
                View {
                    attr {
                        height(54f)
                        flexDirectionRow()
                    }
                    View {
                        attr {
                            flex(1f)
                            alignItemsCenter()
                            justifyContentCenter()
                        }
                        Text {
                            attr {
                                text("取消")
                                fontSize(16f)
                                color(colors().labelSecondary)
                                opacity(if (busy()) 0.4f else 1f)
                            }
                        }
                        event { click { if (!busy()) onCancel() } }
                    }
                    View {
                        attr {
                            width(1f)
                            backgroundColor(colors().borderL1)
                        }
                    }
                    View {
                        attr {
                            flex(1f)
                            alignItemsCenter()
                            justifyContentCenter()
                        }
                        Text {
                            attr {
                                text(if (busy()) "处理中…" else confirmLabel)
                                fontSize(16f)
                                fontWeightMedium()
                                color(confirmColor())
                                opacity(if (busy()) 0.5f else 1f)
                            }
                        }
                        event { click { if (!busy()) onConfirm() } }
                    }
                }
            }
        }
    }
}