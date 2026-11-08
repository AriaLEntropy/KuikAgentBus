package com.kuikagentbus.ui.chat

import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.ImageUri
import com.tencent.kuikly.core.views.Image
import com.tencent.kuikly.core.views.Modal
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 对话页右上角「产物」的整页列表（FR-9.3）
 *
 * 数据就是当前任务的 Task.artifacts，preview 已在页面层滤掉。顺序按电脑给的
 * 「新的在前」，手机不重排。图片行显示缩略图；文件、视频、链接点一行打开。
 */
internal fun ViewContainer<*, *>.BusChatArtifactsPage(
    palette: BusChatPalette,
    topInset: Float,
    artifacts: List<BusChatArtifact>,
    openState: (BusChatArtifact) -> BusChatFileState,
    previewState: (BusChatArtifact) -> BusChatArtifactState,
    onBack: () -> Unit,
    onOpen: (BusChatArtifact) -> Unit,
) {
    Modal(inWindow = true) {
        attr {
            backgroundColor(palette.bgBase)
        }
        View {
            attr {
                absolutePositionAllZero()
                flexDirectionColumn()
                backgroundColor(palette.bgBase)
            }
            // 空白处吃掉点击，避免穿透到下面的对话页
            event { click { } }
            // 顶栏：返回 / 产物 / 内容由 AI 生成；左右各留一颗按钮的宽度让标题真正居中
            View {
                attr {
                    paddingTop(topInset + busChatPx(2f))
                    flexDirectionColumn()
                }
                View {
                    attr {
                        height(BusChatTokens.iconButton)
                        flexDirectionRow()
                        alignItemsCenter()
                        paddingLeft(BusChatTokens.pageSide)
                        paddingRight(BusChatTokens.pageSide)
                    }
                    BusChatIconButton(
                        palette = palette,
                        asset = "chevron-left.svg",
                        iconSize = busChatPx(17f),
                        onClick = onBack,
                    )
                    View {
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            alignItemsCenter()
                        }
                        Text {
                            attr {
                                text("产物")
                                fontSize(busChatPx(17f))
                                fontWeightBold()
                                color(palette.ink)
                            }
                        }
                        Text {
                            attr {
                                text("内容由 AI 生成")
                                fontSize(busChatPx(10.5f))
                                color(palette.mut)
                                marginTop(busChatPx(1f))
                            }
                        }
                    }
                    // 和返回键等宽的空占位，保证标题在页面上居中
                    View {
                        attr {
                            size(BusChatTokens.iconButton, BusChatTokens.iconButton)
                        }
                    }
                }
                View {
                    attr {
                        height(busChatPx(8f))
                    }
                }
            }
            if (artifacts.isEmpty()) {
                View {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        alignItemsCenter()
                        justifyContentCenter()
                        paddingBottom(busChatPx(60f))
                    }
                    Image {
                        attr {
                            src(ImageUri.commonAssets("file-line.svg"))
                            size(busChatPx(34f), busChatPx(34f))
                            tintColor(palette.faint)
                        }
                    }
                    Text {
                        attr {
                            text("这个对话还没有产物")
                            fontSize(busChatPx(13f))
                            color(palette.mut)
                            marginTop(busChatPx(10f))
                        }
                    }
                }
            } else {
                Scroller {
                    attr {
                        flex(1f)
                        showScrollerIndicator(false)
                    }
                    View {
                        attr {
                            flexDirectionColumn()
                            paddingBottom(busChatPx(26f))
                        }
                        artifacts.forEachIndexed { index, artifact ->
                            BusChatArtifactRow(
                                palette = palette,
                                artifact = artifact,
                                state = openState(artifact),
                                preview = previewState(artifact),
                                onOpen = { onOpen(artifact) },
                            )
                            if (index < artifacts.lastIndex) {
                                View {
                                    attr {
                                        height(busChatPx(1f))
                                        marginLeft(busChatPx(72f))
                                        marginRight(busChatPx(20f))
                                        backgroundColor(palette.line)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.BusChatArtifactRow(
    palette: BusChatPalette,
    artifact: BusChatArtifact,
    state: BusChatFileState,
    preview: BusChatArtifactState,
    onOpen: () -> Unit,
) {
    val usable = busChatArtifactUsable(artifact)
    val busy = state.status == BusChatFileStatus.Downloading || state.status == BusChatFileStatus.Fetching
    val failed = state.status == BusChatFileStatus.Failed
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
            paddingLeft(busChatPx(20f))
            paddingRight(busChatPx(20f))
            paddingTop(busChatPx(12f))
            paddingBottom(busChatPx(12f))
            opacity(if (usable) 1f else 0.45f)
        }
        event {
            click {
                if (usable && !busy) onOpen()
            }
        }
        // 图片有本地缩略图就画图，其余按类型给线性图标
        if (artifact.kind == "image" && preview.status == BusChatArtifactStatus.Ready) {
            Image {
                attr {
                    size(busChatPx(40f), busChatPx(40f))
                    borderRadius(busChatPx(8f))
                    src(ImageUri.file(preview.path))
                    resizeCover()
                }
            }
        } else {
            View {
                attr {
                    size(busChatPx(40f), busChatPx(40f))
                    borderRadius(busChatPx(8f))
                    backgroundColor(palette.field)
                    alignItemsCenter()
                    justifyContentCenter()
                }
                Image {
                    attr {
                        src(ImageUri.commonAssets(artifactIcon(artifact)))
                        size(busChatPx(22f), busChatPx(22f))
                        tintColor(if (failed) palette.bad else palette.deep)
                    }
                }
            }
        }
        View {
            attr {
                flex(1f)
                flexDirectionColumn()
                marginLeft(busChatPx(12f))
            }
            Text {
                attr {
                    text(if (usable) artifactTitle(artifact) else BUS_CHAT_ARTIFACT_UNAVAILABLE)
                    fontSize(busChatPx(14.5f))
                    fontWeightMedium()
                    color(palette.ink)
                    lines(1)
                    textOverFlowTail()
                }
            }
            Text {
                attr {
                    text(
                        when {
                            state.label.isNotEmpty() -> state.label
                            !usable -> ""
                            artifact.kind == "link" -> "链接"
                            else -> artifactMeta(artifact)
                        },
                    )
                    fontSize(busChatPx(11f))
                    color(if (failed) palette.bad else palette.mut)
                    lines(1)
                    textOverFlowTail()
                    marginTop(busChatPx(2f))
                }
            }
        }
        if (busy) {
            Text {
                attr {
                    text("…")
                    fontSize(busChatPx(13f))
                    color(palette.mut)
                }
            }
        }
    }
}

private fun artifactTitle(artifact: BusChatArtifact): String {
    val title = artifact.title.trim()
    if (title.isNotEmpty()) return title
    return when (artifact.kind) {
        "link" -> artifact.url.ifBlank { "链接" }
        "image" -> "图片"
        "video" -> "视频"
        else -> "产物"
    }
}

private fun artifactMeta(artifact: BusChatArtifact): String {
    val size = busChatFileSize(artifact.size)
    val kind = when (artifact.kind) {
        "image" -> "图片"
        "video" -> "视频"
        "file" -> "文件"
        else -> "产物"
    }
    return if (size.isEmpty()) kind else "$size · $kind"
}

private fun artifactIcon(artifact: BusChatArtifact): String =
    if (artifact.kind == "link") "icon-link16.svg" else "file-line.svg"
