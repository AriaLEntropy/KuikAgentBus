package com.kuikagentbus.ui.chat

import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.layout.FlexPositionType
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 待发送图片的缩略图条（FR-9.1）：上传中转半透明、失败可点重试、右上角可移除 */
internal fun ViewContainer<*, *>.BusChatImageTray(
    palette: BusChatPalette,
    images: List<BusChatPendingImage>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    if (images.isEmpty()) return
    View {
        attr {
            flexDirectionRow()
            flexWrap(FlexWrap.WRAP)
            marginBottom(busChatPx(2f))
        }
        images.forEach { image ->
            if (image.kind == "file") {
                BusChatPendingFileTile(
                    palette = palette,
                    image = image,
                    onRemove = onRemove,
                    onRetry = onRetry,
                )
                return@forEach
            }
            View {
                attr {
                    size(busChatPx(58f), busChatPx(58f))
                    borderRadius(busChatPx(10f))
                    marginRight(busChatPx(8f))
                    marginBottom(busChatPx(6f))
                    backgroundColor(palette.field)
                    border(
                        Border(
                            1f,
                            BorderStyle.SOLID,
                            if (image.status == BusChatImageStatus.Failed) palette.bad else palette.line,
                        ),
                    )
                }
                event {
                    click {
                        if (image.status == BusChatImageStatus.Failed) onRetry(image.localId)
                    }
                }
                Image {
                    attr {
                        size(busChatPx(56f), busChatPx(56f))
                        borderRadius(busChatPx(9f))
                        src(image.dataUrl)
                        resizeCover()
                        opacity(if (image.status == BusChatImageStatus.Uploading) 0.55f else 1f)
                    }
                }
                if (image.status != BusChatImageStatus.Ready) {
                    View {
                        attr {
                            positionType(FlexPositionType.ABSOLUTE)
                            left(busChatPx(0f))
                            right(busChatPx(0f))
                            top(busChatPx(0f))
                            bottom(busChatPx(0f))
                            borderRadius(busChatPx(9f))
                            backgroundColor(if (image.status == BusChatImageStatus.Failed) Color(0x99000000) else Color(0x66000000))
                            alignItemsCenter()
                            justifyContentCenter()
                        }
                        event {
                            click {
                                if (image.status == BusChatImageStatus.Failed) onRetry(image.localId)
                            }
                        }
                        Text {
                            attr {
                                text(if (image.status == BusChatImageStatus.Failed) "重试" else "上传中")
                                fontSize(busChatPx(9.5f))
                                color(Color(0xFFFFFFFF))
                            }
                        }
                    }
                }
                // 右上角移除：点缩略图是重试，点这颗小叉才是删除
                View {
                    attr {
                        positionType(FlexPositionType.ABSOLUTE)
                        top(busChatPx(-5f))
                        right(busChatPx(-5f))
                        size(busChatPx(18f), busChatPx(18f))
                        borderRadius(busChatPx(9f))
                        backgroundColor(palette.surface)
                        border(Border(1f, BorderStyle.SOLID, palette.line))
                        alignItemsCenter()
                        justifyContentCenter()
                    }
                    event { click { onRemove(image.localId) } }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("x.svg"))
                            size(busChatPx(8f), busChatPx(8f))
                            tintColor(palette.mut)
                        }
                    }
                }
            }
        }
    }
}

/** 普通文件附件：一条带文件名的窄卡，上传/失败状态放在副行 */
private fun ViewContainer<*, *>.BusChatPendingFileTile(
    palette: BusChatPalette,
    image: BusChatPendingImage,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val failed = image.status == BusChatImageStatus.Failed
    View {
        attr {
            width(busChatPx(148f))
            height(busChatPx(58f))
            borderRadius(busChatPx(10f))
            marginRight(busChatPx(8f))
            marginBottom(busChatPx(6f))
            backgroundColor(palette.surface)
            border(Border(1f, BorderStyle.SOLID, if (failed) palette.bad else palette.line))
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(busChatPx(8f))
            paddingRight(busChatPx(8f))
        }
        event {
            click {
                if (failed) onRetry(image.localId)
            }
        }
        View {
            attr {
                size(busChatPx(32f), busChatPx(32f))
                borderRadius(busChatPx(8f))
                backgroundColor(palette.field)
                alignItemsCenter()
                justifyContentCenter()
            }
            Image {
                attr {
                    src(ImageUri.commonAssets("file-line.svg"))
                    size(busChatPx(18f), busChatPx(18f))
                    tintColor(if (failed) palette.bad else palette.deep)
                }
            }
        }
        View {
            attr {
                flex(1f)
                flexDirectionColumn()
                marginLeft(busChatPx(7f))
                marginRight(busChatPx(4f))
            }
            Text {
                attr {
                    text(image.name)
                    fontSize(busChatPx(10.5f))
                    lineHeight(busChatPx(12.5f))
                    fontWeightSemiBold()
                    color(palette.ink)
                    lines(2)
                    textOverFlowTail()
                }
            }
            Text {
                attr {
                    text(
                        when (image.status) {
                            BusChatImageStatus.Uploading -> "上传中"
                            BusChatImageStatus.Failed -> image.error.ifBlank { "重试" }
                            BusChatImageStatus.Ready -> busChatFileSize(image.size)
                        },
                    )
                    fontSize(busChatPx(9.5f))
                    color(if (failed) palette.bad else palette.mut)
                    lines(1)
                    textOverFlowTail()
                    marginTop(busChatPx(1f))
                }
            }
        }
        View {
            attr {
                positionType(FlexPositionType.ABSOLUTE)
                top(busChatPx(-5f))
                right(busChatPx(-5f))
                size(busChatPx(18f), busChatPx(18f))
                borderRadius(busChatPx(9f))
                backgroundColor(palette.surface)
                border(Border(1f, BorderStyle.SOLID, palette.line))
                alignItemsCenter()
                justifyContentCenter()
            }
            event { click { onRemove(image.localId) } }
            Image {
                attr {
                    src(ImageUri.commonAssets("x.svg"))
                    size(busChatPx(8f), busChatPx(8f))
                    tintColor(palette.mut)
                }
            }
        }
    }
}
