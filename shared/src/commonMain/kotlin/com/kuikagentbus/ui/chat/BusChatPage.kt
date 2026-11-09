package com.kuikagentbus.ui.chat

import com.kuikagentbus.base.BasePager
import com.kuikagentbus.base.BridgeModule
import com.kuikagentbus.protocol.BUS_DELETE_TASK_CONFIRM_MESSAGE
import com.kuikagentbus.protocol.BUS_DELETE_TASK_CONFIRM_TITLE
import com.kuikagentbus.protocol.BUS_UPLOAD_MAX_IMAGES
import com.kuikagentbus.protocol.BusUploadAttachment
import com.kuikagentbus.protocol.busCanDeleteTask
import com.kuikagentbus.protocol.busUploadContentTypeAllowed
import com.kuikagentbus.protocol.busUploadFileTypeAllowed
import com.kuikagentbus.protocol.busUploadSizeAllowed
import com.kuikagentbus.data.connection.DshRelayModule
import com.kuikagentbus.data.message.DshMessage
import com.kuikagentbus.data.message.DshMessageAttachment
import com.kuikagentbus.data.message.DshMessageFileRef
import com.kuikagentbus.data.storage.DshLocalStore
import com.kuikagentbus.data.storage.createDshLocalStore
import com.kuikagentbus.platform.currentTimeMillis
import com.kuikagentbus.theme.DshColorTokens
import com.kuikagentbus.ui.dshTopInset
import com.kuikagentbus.ui.home.BusAgentVisuals
import com.kuikagentbus.ui.home.BusHomeModelOption
import com.kuikagentbus.ui.home.taskStatusLabel
import com.kuikagentbus.ui.session.DshSessionConfirmDialog
import com.kuikagentbus.ui.rendering.DshTextInputDialog
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.BackPressCallback
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.scrollToPosition
import com.tencent.kuikly.core.directives.vbind
import com.tencent.kuikly.core.directives.vforLazy
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.List
import com.tencent.kuikly.core.views.ListView
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 任务详情 / 对话页
 *
 * 手机端不重新实现 Agent 逻辑，只把电脑上已经在跑的那一轮接着显示出来。
 * 三种真状态（流式 / 待处理 / 已结束）都落在输入条右下角那颗键上，顶栏不挂状态徽标
 */
/**
 * 消息区的渲染行：消息、更早提示、流式行、审批卡、产物卡统一进一个虚拟列表
 *
 * 审批卡和产物卡以前是 List 的静态兄弟节点，虚拟列表算不到它们的高度，
 * 自动落底会差一截；收进同一组行之后，落底就是最后一个索引
 */
private sealed class BusChatFeedItem {
    abstract val key: String

    data object Hint : BusChatFeedItem() {
        override val key: String = "hint"
    }

    data class MessageRow(val row: BusChatRow) : BusChatFeedItem() {
        override val key: String = row.key
    }

    data object Streaming : BusChatFeedItem() {
        override val key: String = "streaming"
    }

    data class Approval(val request: BusChatPendingRequest) : BusChatFeedItem() {
        override val key: String = "approval-${request.requestId}"
    }

    data class Artifact(val artifact: BusChatArtifact) : BusChatFeedItem() {
        override val key: String = "artifact-${artifact.id}"
    }
}

/** 相邻渲染行的上间距：换轮 24 / 同轮 8 / 工具后正文 14 / 卡片 12 */
private fun busChatFeedGap(previous: BusChatFeedItem?, current: BusChatFeedItem): Float = when {
    previous == null || previous is BusChatFeedItem.Hint -> 0f
    current is BusChatFeedItem.MessageRow ->
        busChatRowGap((previous as? BusChatFeedItem.MessageRow)?.row, current.row)
    current is BusChatFeedItem.Streaming -> busChatPx(14f)
    else -> busChatPx(12f)
}

@Page("bus_chat")
internal class BusChatPage : BasePager() {
    private var taskId by observable("")
    private var computerAgentId = ""
    private var sourceKind by observable("")
    private var connectorId by observable("")
    private var agentKey by observable("")
    private var agentName by observable("")
    private var hostName by observable("")
    private var projectPath by observable("")
    private var projectName by observable("")
    private var projectsRoot = ""
    /** FR-8.2：已选中的新建项目名；非空时 startTask 带 newProject */
    private var newProjectName by observable("")
    private var showNewProjectDialog by observable(false)
    private var newProjectDraft by observable("")
    private var newProjectError by observable("")
    private var modelId by observable("")
    private var effort by observable("")
    private var autoApprove by observable(false)
    private var canAutoApprove by observable(false)
    /** 用户刚选的模型 / 强度 / 自动批准：随下一条消息发出，快照回来前不被覆盖 */
    private var stagedModelId by observable("")
    private var stagedEffort by observable("")
    private var stagedAutoApprove by observable<Boolean?>(null)

    private var title by observable("")
    private var status by observable("")
    private var statusLabel by observable("")
    private var controllable by observable(true)
    private var canDeleteTasks by observable(false)
    private var showDeleteConfirm by observable(false)
    private var deleteBusy by observable(false)
    private var deleteError by observable("")
    private var showRemoveConfirm by observable(false)
    private var draft by observable("")
    /** FR-9.1：还没发出去的图，状态和 artifactId 都在这里 */
    private val pendingImages by observableList<BusChatPendingImage>()
    private var pendingImageSeq = 0L
    private var sending by observable(false)
    private var error by observable("")
    private var loading by observable(false)
    private var hasMore by observable(false)
    private var lastMessage by observable("")
    /** 完整 IME inset（dp，含导航栏）；对话页用 adjustNothing，窗口不会自己让位 */
    private var keyboardHeight by observable(0f)
    private var inputFocused by observable(false)
    private var focusBeforeSheet = false
    private var keyboardScrollToken = 0
    private var bridgeModule: BridgeModule? = null
    private var pendingRequest by observable<BusChatPendingRequest?>(null)
    private var requestBusy by observable(false)
    private var requestError by observable("")
    private var answeredRequestId by observable("")
    private var answerSelection by observable<Map<String, List<String>>>(emptyMap())
    private var showAutoApproveConfirm by observable(false)
    private var autoApproveExplained = false
    /** FR-8.5：首次对这台电脑上的这个 Agent 发任务前要确认一次 */
    private var showChatConsent by observable(false)
    private var store: DshLocalStore? = null
    /** 草稿态的 chips 只有用户主动选过才回显名字 */
    private var workspacePicked by observable(false)
    private var agentPicked by observable(false)

    private var showMenu by observable(false)
    private var showArtifacts by observable(false)
    private var artifactRevision by observable(0)
    private var sheet by observable(BusChatSheetKind.NONE)

    /** 就地展开的那一组工具行的 key（稿子③）；空串表示都收着，同一时刻只展开一组 */
    private var expandedToolsKey by observable("")
    private var stepIndex by observable(0)
    private var stepTitle by observable("")
    private var listScrolled by observable(false)
    private var atBottom by observable(true)
    private var toast by observable("")
    private val stepTools by observableList<DshMessage>()

    private val messages by observableList<DshMessage>()
    private val chatRows by observableList<BusChatRow>()
    private val feedRows by observableList<BusChatFeedItem>()
    private val models by observableList<BusHomeModelOption>()
    private val agents by observableList<BusChatAgentOption>()
    private val artifacts by observableList<BusChatArtifact>()
    private val skills by observableList<BusChatSkill>()
    private var skillsRevision by observable(0)
    private var skillsLoading by observable(false)
    private var skillsError by observable("")
    private var skillContentTitle by observable("")
    private var skillContentBody by observable("")
    private var skillContentLoading by observable(false)
    private var skillContentError by observable("")
    private val workspaces by observableList<BusChatWorkspace>()

    /**
     * FR-9.2：产物字节的取回状态，键是 artifactId
     *
     * 渲染缩略图时才查这个表，没见过就顺手发起下载；Feed 用 vforLazy，
     * 只有进屏的行会执行，等于需求里那句「出现图片 → 没有就下载」
     */
    private var artifactStates by observable<Map<String, BusChatArtifactState>>(emptyMap())

    /** 在途的 artifactId，防同一张图被连续重组请求多次 */
    private val artifactInFlight = mutableSetOf<String>()

    /** FR-9.4：文件卡片的状态，键是 busChatFileKey(messageId, path) */
    private var fileStates by observable<Map<String, BusChatFileState>>(emptyMap())

    /** 等电脑重发记录的节奏：1.5 秒一次、最多 8 次，之后按超时收口，不让卡片卡在加载中 */
    private val fileIdPollMs = 1_500
    private val fileIdMaxAttempts = 8

    private var chatSource: ConversationSource? = null
    private var inputView: InputView? = null
    private var chatListRef: ViewRef<ListView<*, *>>? = null
    private var alive = true
    private var lastFetchAt = 0L
    private var lastStatus = ""

    override fun created() {
        super.created()
        // 390×844 设计稿按页面宽度等比缩放；created 里先设一次，避免首屏用到默认值
        BusChatTokens.updateDesignScale(pagerData.pageViewWidth)
        bridgeModule = runCatching { acquireModule<BridgeModule>(BridgeModule.MODULE_NAME) }.getOrNull()
        getBackPressHandler().addCallback(object : BackPressCallback() {
            override fun handleOnBackPressed() {
                when {
                    // 产物整页在最上层，返回先关它
                    showArtifacts -> showArtifacts = false
                    showNewProjectDialog -> showNewProjectDialog = false
                    showChatConsent -> showChatConsent = false
                    showAutoApproveConfirm -> showAutoApproveConfirm = false
                    showRemoveConfirm -> showRemoveConfirm = false
                    showDeleteConfirm -> showDeleteConfirm = false
                    showMenu -> showMenu = false
                    // 单步详情返回上一层列表，而不是直接关掉整个面板
                    sheet == BusChatSheetKind.STEP_DETAIL -> sheet = BusChatSheetKind.STEPS
                    sheet != BusChatSheetKind.NONE -> sheet = BusChatSheetKind.NONE
                    // 键盘还开着时先收键盘；系统通常已经吃掉第一下 back，
                    // 所以这里用「键盘高度」而不是焦点判断，避免再空按一次
                    keyboardHeight > 0f -> inputView?.blur()
                    else -> close()
                }
            }
        })
        val params = pageData.params
        taskId = params.optString("taskId")
        computerAgentId = params.optString("agentId")
        sourceKind = params.optString("source")
        connectorId = params.optString("connectorId")
        agentKey = params.optString("agentKey")
        agentName = params.optString("agentName")
        hostName = params.optString("hostName")
        val databaseDir = params.optString("databaseDir")
        store = if (databaseDir.isBlank()) {
            null
        } else {
            runCatching { createDshLocalStore("$databaseDir/dsh.db", null) }.getOrNull()
        }
        projectPath = params.optString("projectPath")
        projectName = params.optString("projectName")
        projectsRoot = params.optString("projectsRoot")
        modelId = params.optString("model")
        effort = params.optString("effort")
        autoApprove = params.optString("autoApprove") == "true"
        canAutoApprove = params.optString("canAutoApprove") == "true"
        // 草稿态一律用「新建任务」，不沿用列表里带过来的旧标题
        title = if (taskId.isEmpty()) "新建任务" else params.optString("title").ifBlank { "对话" }
        controllable = params.optString("controllable") != "false"

        val relay = acquireModule<DshRelayModule>(DshRelayModule.MODULE_NAME)
        val source: ConversationSource = BusChatSource(relay, pagerId)
        chatSource = source
        agents.clear()
        agents.addAll(source.agents())
        // 首页没给 Agent 时选第一个可启动的；给了就把它作为默认选中
        val current = agents.firstOrNull { it.key == agentKey } ?: agents.firstOrNull()
        if (current != null) {
            if (agentKey.isBlank()) {
                agentKey = current.key
                agentName = current.name
                sourceKind = current.source
                connectorId = current.connectorId.orEmpty()
            }
            canAutoApprove = current.canAutoApprove
        }
        source.models(agentKey).let { list ->
            models.clear()
            models.addAll(list)
        }
        if (taskId.isEmpty()) {
            // 新建任务的默认项目 / Agent 直接显示成已选中，不让用户以为还没选
            agentPicked = agentKey.isNotBlank()
            workspacePicked = projectPath.isNotBlank()
        }
        skills.clear()
        workspaces.clear()
        workspaces.addAll(source.workspaces())
        if (taskId.isNotEmpty()) {
            artifacts.clear()
            // FR-9.3：preview 不进列表，菜单里那句计数也跟着只数能看的
            artifacts.addAll(busChatListableArtifacts(source.artifacts(taskId)))
        }
        if (taskId.isNotEmpty()) {
            loading = true
            applyHistory()
            fetchMessages(force = true)
        }
        refreshFeed()
        pollTask()
        pollMessages()
        syncNavigationBarColor()
    }

    override fun onThemeSynced() {
        syncNavigationBarColor()
        syncSystemBarsDim()
    }

    override fun pageWillDestroy() {
        alive = false
        inputFocused = false
        keyboardHeight = 0f
        bridgeModule = null
        runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME).setSystemBarsDimmed(false)
        }
        runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
                .setNavigationBarColor(0x00000000L, themeColors.isDark)
        }
        super.pageWillDestroy()
    }

    private fun syncNavigationBarColor() {
        val argb = if (themeColors.isDark) 0xFF151517 else 0xFFF4F4F6
        runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
                .setNavigationBarColor(argb, themeColors.isDark)
        }
    }

    /** 全屏浮层（对话框 / 底部面板）出现时，把系统导航栏一起压暗，避免遮罩在底部断一条亮带 */
    private fun overlayVisible(): Boolean =
        showNewProjectDialog ||
            showChatConsent ||
            showAutoApproveConfirm ||
            showRemoveConfirm ||
            showDeleteConfirm ||
            sheet != BusChatSheetKind.NONE

    private fun syncSystemBarsDim() {
        if (!pagerData.isAndroid && !pagerData.isIOS) return
        runCatching {
            acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
                .setSystemBarsDimmed(overlayVisible(), overlayDimColor())
        }
    }

    /**
     * 导航栏要贴合当前浮层：
     * 确认弹窗用遮罩结果色；底部面板直接用面板底色，让面板看起来一直铺到屏幕底，不露背景条
     */
    private fun overlayDimColor(): Long {
        val dialogOnTop = showNewProjectDialog || showChatConsent || showAutoApproveConfirm || showRemoveConfirm || showDeleteConfirm
        if (dialogOnTop) {
            return if (themeColors.isDark) 0xFF0D0D0EL else 0xFF929296L
        }
        return busChatPalette(themeColors).sheet.hexColor
    }

    private fun applyHistory() {
        val history = chatSource?.history(taskId) ?: return
        hasMore = history.hasMore
        if (history.messages == messages.toList()) {
            loading = false
            return
        }
        messages.clear()
        messages.addAll(history.messages)
        refreshChatRows()
        loading = false
        if (atBottom) scrollToBottom()
    }

    private fun refreshChatRows() {
        chatRows.diffUpdate(busChatRows(messages.toList())) { old, new -> old == new }
        refreshFeed()
    }

    /** 把消息、提示、流式行、审批卡与产物卡重算成一组可落底的行 */
    private fun refreshFeed() {
        val next = ArrayList<BusChatFeedItem>(chatRows.size + 4)
        if (hasMore) next += BusChatFeedItem.Hint
        chatRows.forEach { next += BusChatFeedItem.MessageRow(it) }
        if (isRunning()) next += BusChatFeedItem.Streaming
        val request = pendingRequest
        if (request != null && showApprovalCard()) next += BusChatFeedItem.Approval(request)
        artifacts.take(2).forEach { next += BusChatFeedItem.Artifact(it) }
        feedRows.diffUpdate(next)
        if (atBottom) setTimeout(pagerId, 50) { scrollToBottomNow() }
    }

    private fun fetchMessages(force: Boolean) {
        if (taskId.isEmpty()) return
        val now = currentTimeMillis()
        if (!force && now - lastFetchAt < 3_000) return
        lastFetchAt = now
        chatSource?.fetchMessages(computerAgentId, taskId)
    }

    private fun pollMessages() {
        if (!alive) return
        if (taskId.isNotEmpty()) {
            applyHistory()
            fetchMessages(force = false)
        }
        setTimeout(pagerId, 1_500) { pollMessages() }
    }

    private fun pollTask() {
        if (!alive) return
        if (taskId.isNotEmpty()) {
            val info = chatSource?.taskInfo(taskId)
            if (info != null) {
                if (info.title.isNotBlank()) title = info.title
                if (info.agentName.isNotBlank()) agentName = info.agentName
                if (info.projectName.isNotBlank()) projectName = info.projectName
                if (stagedModelId.isBlank() && info.model.isNotBlank()) modelId = info.model
                if (stagedEffort.isBlank() && info.effort.isNotBlank()) effort = info.effort
                controllable = info.controllable
                canDeleteTasks = info.canDeleteTasks
                canAutoApprove = info.canAutoApprove
                if (stagedAutoApprove == null && info.autoApprove != autoApprove) {
                    autoApprove = info.autoApprove
                }
                lastMessage = info.lastMessage
                status = info.status
                statusLabel = info.statusLabel.ifBlank { taskStatusLabel(info.status) }
                val nextRequest = info.request
                if (nextRequest?.requestId != pendingRequest?.requestId) {
                    pendingRequest = nextRequest
                    requestError = ""
                    answeredRequestId = ""
                    answerSelection = emptyMap()
                    if (atBottom) scrollToBottom()
                }
                if (lastStatus == "running" && info.status != "running") {
                    fetchMessages(force = true)
                    setTimeout(pagerId, 800) { applyHistory() }
                }
                lastStatus = info.status
                refreshFeed()
            }
        }
        setTimeout(pagerId, 2_000) { pollTask() }
    }

    private fun stagedModel(): String = stagedModelId.ifBlank { modelId }

    private fun stagedEffortValue(): String = stagedEffort.ifBlank { effort }

    private fun stagedAutoApproveValue(): Boolean = stagedAutoApprove ?: autoApprove

    private fun modelLabel(): String {
        val id = stagedModel()
        val model = models.firstOrNull { it.id == id }
        return busChatModelChipLabel(
            displayName = model?.displayName.orEmpty(),
            modelId = id,
            effort = stagedEffortValue(),
        )
    }

    private fun workspaceLabel(): String =
        if (newProjectName.isNotBlank()) newProjectName
        else if (projectPath.isBlank()) "不在项目中"
        else projectName.ifBlank { projectPath.substringAfterLast('/') }

    private fun avatarAsset(): String = BusAgentVisuals.assetFor(
        source = sourceKind,
        connectorId = connectorId,
        agentKey = agentKey,
        agentName = agentName,
    )

    private fun avatarOnly(): Boolean {
        val asset = avatarAsset()
        return asset.isNotBlank() && asset != BusAgentVisuals.FALLBACK
    }

    private fun isReadOnly(): Boolean = taskId.isNotEmpty() && !controllable

    /** FR-9.1：目前只对已知能收图的 acp / dsh 显示图片入口 */
    private fun attachmentSupported(): Boolean = sourceKind == "acp" || sourceKind == "dsh"

    private fun isRunning(): Boolean = status == "running"

    private fun canDelete(): Boolean =
        taskId.isNotEmpty() && busCanDeleteTask(status, canDeleteTasks)

    private fun showApprovalCard(): Boolean {
        val request = pendingRequest ?: return false
        return request.requestId.isNotBlank() && request.requestId != answeredRequestId
    }

    private fun scrollToBottom() {
        atBottom = true
        // 审批卡/流式行/产物是异步挂上的，内容高度会晚一拍；多排几次落底
        setTimeout(pagerId, 60) { scrollToBottomNow() }
        setTimeout(pagerId, 260) { scrollToBottomNow() }
        setTimeout(pagerId, 600) { scrollToBottomNow() }
    }

    private fun scrollToBottomNow() {
        val list = chatListRef?.view ?: return
        val last = feedRows.lastIndex
        if (last >= 0) list.scrollToPosition(last, 0f, false)
    }

    private fun onListScroll(offsetY: Float, contentHeight: Float, viewHeight: Float) {
        listScrolled = offsetY > 1f
        atBottom = offsetY + viewHeight >= contentHeight - busChatPx(24f)
    }

    private fun showToast(text: String) {
        toast = text
        setTimeout(pagerId, 2200) {
            if (toast == text) toast = ""
        }
    }

    /**
     * Kuikly 在 Android 11+ 上报的是 ime - navigationBars；对话页是 adjustNothing，
     * 物理底边到键盘顶的距离必须用原生完整 inset，否则会少抬一个导航栏。
     */
    private fun fullKeyboardInset(reported: Float): Float {
        if (reported <= 0f) return 0f
        val native = bridgeModule?.imeInsetBottom() ?: -1f
        return if (native > 0f) native else reported
    }

    /** 打开输入类浮层前先让出键盘，并记住来的时候输入框有没有焦点 */
    private fun openSheet(kind: BusChatSheetKind) {
        // 从浮层里再进下一层（技能列表 → 技能正文）时不要覆盖第一次记下的焦点状态
        if (sheet == BusChatSheetKind.NONE) focusBeforeSheet = inputFocused
        inputView?.blur()
        sheet = kind
    }

    /** 选择完浮层内容后回到输入条；只有来的时候有焦点才重新叫键盘 */
    private fun restoreComposerFocus(restoreText: Boolean = false) {
        if (!focusBeforeSheet) return
        focusBeforeSheet = false
        // Agent 变化会整页重建，等新输入框挂上再回填/聚焦
        setTimeout(pagerId, 200) {
            if (!alive) return@setTimeout
            if (restoreText) inputView?.setText(draft)
            inputView?.focus()
        }
    }

    // ---------- FR-9.1 图片附件 ----------

    private fun pickImages(source: String) {
        val bridge = bridgeModule ?: return
        if (pendingImages.size >= BUS_UPLOAD_MAX_IMAGES) {
            showToast("一次最多 $BUS_UPLOAD_MAX_IMAGES 张图片")
            return
        }
        bridge.pickImage(source, compress = true) { raw ->
            if (!alive) return@pickImage
            handlePickedImages(raw)
        }
    }

    private fun handlePickedImages(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (root.optBoolean("cancelled")) return
        if (!root.optBoolean("ok")) {
            showToast(root.optString("error").ifBlank { "选择图片失败" })
            return
        }
        val list = root.optJSONArray("images") ?: JSONArray()
        var added = 0
        for (index in 0 until list.length()) {
            if (pendingImages.size >= BUS_UPLOAD_MAX_IMAGES) break
            val item = list.optJSONObject(index) ?: continue
            val dataUrl = item.optString("dataUrl")
            val contentType = item.optString("mediaType")
            val bytes = item.optString("bytes").toLongOrNull() ?: 0L
            val width = item.optString("width").toIntOrNull() ?: 0
            val height = item.optString("height").toIntOrNull() ?: 0
            if (dataUrl.isEmpty() || contentType.isEmpty()) continue
            if (!busUploadContentTypeAllowed(contentType)) {
                showToast("这张图片格式不支持")
                continue
            }
            if (!busUploadSizeAllowed(bytes)) {
                showToast("图片超过 2 MB")
                continue
            }
            val image = BusChatPendingImage(
                localId = "img-${++pendingImageSeq}",
                kind = "image",
                dataUrl = dataUrl,
                contentType = contentType,
                name = item.optString("name").ifBlank { "图片" },
                size = bytes,
                width = width,
                height = height,
            )
            pendingImages.add(image)
            uploadPendingImage(image)
            added++
        }
        if (added == 0 && list.length() == 0) {
            showToast("没有选到图片")
        }
    }

    private fun pickFiles() {
        val bridge = bridgeModule ?: return
        if (pendingImages.size >= BUS_UPLOAD_MAX_IMAGES) {
            showToast("一次最多 $BUS_UPLOAD_MAX_IMAGES 个附件")
            return
        }
        bridge.pickFile { raw ->
            if (!alive) return@pickFile
            handlePickedFiles(raw)
        }
    }

    private fun handlePickedFiles(raw: String) {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (root.optBoolean("cancelled")) return
        if (!root.optBoolean("ok")) {
            showToast(root.optString("error").ifBlank { "选择文件失败" })
            return
        }
        val list = root.optJSONArray("files") ?: JSONArray()
        for (index in 0 until list.length()) {
            if (pendingImages.size >= BUS_UPLOAD_MAX_IMAGES) break
            val item = list.optJSONObject(index) ?: continue
            val dataUrl = item.optString("dataUrl")
            val contentType = item.optString("mediaType")
            val name = item.optString("name").ifBlank { "附件" }
            val bytes = item.optString("bytes").toLongOrNull() ?: 0L
            if (dataUrl.isEmpty() || contentType.isEmpty()) continue
            if (!busUploadFileTypeAllowed(contentType)) {
                showToast("暂不支持 ${name.substringAfterLast('.', "这个文件")} 类型的文件")
                continue
            }
            if (!busUploadSizeAllowed(bytes)) {
                showToast("$name 超过 2 MB")
                continue
            }
            val pending = BusChatPendingImage(
                localId = "file-${++pendingImageSeq}",
                kind = "file",
                dataUrl = dataUrl,
                contentType = contentType,
                name = name,
                size = bytes,
                width = 0,
                height = 0,
            )
            pendingImages.add(pending)
            uploadPendingImage(pending)
        }
    }

    private fun uploadPendingImage(image: BusChatPendingImage) {
        val source = chatSource ?: return
        updatePendingImage(image.localId) {
            it.copy(status = BusChatImageStatus.Uploading, error = "")
        }
        source.uploadAttachment(image.dataUrl, image.contentType, image.name) { result ->
            if (!alive) return@uploadAttachment
            if (result.uploaded) {
                updatePendingImage(image.localId) {
                    it.copy(
                        status = BusChatImageStatus.Ready,
                        artifactId = result.artifactId,
                        size = result.size.takeIf { size -> size > 0L } ?: it.size,
                    )
                }
            } else {
                updatePendingImage(image.localId) {
                    it.copy(
                        status = BusChatImageStatus.Failed,
                        error = busChatUploadFailure(result.state, result.reason),
                    )
                }
            }
        }
    }

    private fun updatePendingImage(localId: String, transform: (BusChatPendingImage) -> BusChatPendingImage) {
        val index = pendingImages.indexOfFirst { it.localId == localId }
        if (index < 0) return
        pendingImages[index] = transform(pendingImages[index])
    }

    private fun removePendingImage(localId: String) {
        val index = pendingImages.indexOfFirst { it.localId == localId }
        if (index >= 0) pendingImages.removeAt(index)
    }

    private fun retryPendingImage(localId: String) {
        val image = pendingImages.firstOrNull { it.localId == localId } ?: return
        if (image.status == BusChatImageStatus.Failed) uploadPendingImage(image)
    }

    private fun clearPendingImages() {
        pendingImages.clear()
    }

    private fun send() {
        if (sending || isReadOnly()) return
        val text = draft.trim()
        val images = pendingImages.toList()
        if (images.any { it.status == BusChatImageStatus.Uploading }) {
            showToast("附件还在上传")
            return
        }
        if (images.any { it.status != BusChatImageStatus.Ready }) {
            showToast("有附件上传失败，先重试或移除")
            return
        }
        if (text.isEmpty() && images.isEmpty()) return
        val attachments = images.map {
            BusUploadAttachment(it.artifactId, it.contentType, it.size, it.width, it.height)
        }
        if (!hasChatConsent()) {
            showChatConsent = true
            return
        }
        error = ""
        sending = true
        sheet = BusChatSheetKind.NONE
        if (taskId.isEmpty()) startTask(text, attachments) else followUp(text, attachments)
    }

    private fun chatConsentKey(): String = "bus_chat_consent_${computerAgentId}_${agentKey}"

    private fun hasChatConsent(): Boolean =
        store == null || store?.loadSetting(chatConsentKey()) == "true"

    private fun rememberChatConsent() {
        store?.saveSetting(chatConsentKey(), "true")
    }

    private fun startTask(prompt: String, attachments: List<BusUploadAttachment>) {
        val request = BusChatStartRequest(
            agentId = computerAgentId,
            source = sourceKind,
            connectorId = connectorId.ifBlank { null },
            projectPath = projectPath,
            newProject = newProjectName.takeIf { it.isNotBlank() },
            prompt = prompt,
            model = stagedModel(),
            effort = stagedEffortValue(),
            autoApprove = stagedAutoApproveValue().takeIf {
                canAutoApprove && projectPath.isNotBlank()
            },
            attachments = attachments,
        )
        chatSource?.startTask(request) { id, message ->
            sending = false
            if (id == null) {
                error = message ?: "创建失败"
                return@startTask
            }
            taskId = id
            title = prompt.take(40).ifBlank {
                if (attachments.all { it.contentType.startsWith("image/") }) "图片" else "附件"
            }
            status = "running"
            statusLabel = "运行中"
            controllable = true
            clearDraft()
            clearPendingImages()
            newProjectName = ""
            clearStaged()
            loading = true
            atBottom = true
            fetchMessages(force = true)
            setTimeout(pagerId, 800) { applyHistory() }
        }
    }

    private fun followUp(prompt: String, attachments: List<BusUploadAttachment>) {
        chatSource?.followUp(
            agentId = computerAgentId,
            taskId = taskId,
            prompt = prompt,
            model = stagedModel(),
            effort = stagedEffortValue(),
            autoApprove = stagedAutoApproveValue().takeIf {
                canAutoApprove && projectPath.isNotBlank()
            },
            attachments = attachments,
        ) { ok, message ->
            sending = false
            if (!ok) {
                error = message.ifBlank { "发送失败" }
                return@followUp
            }
            clearDraft()
            clearPendingImages()
            newProjectName = ""
            clearStaged()
            status = "running"
            statusLabel = "运行中"
            atBottom = true
            setTimeout(pagerId, 600) {
                fetchMessages(force = true)
                applyHistory()
                scrollToBottom()
            }
            setTimeout(pagerId, 2_500) {
                fetchMessages(force = true)
                applyHistory()
            }
        }
    }

    private fun clearDraft() {
        draft = ""
        inputView?.setText("")
    }

    private fun clearStaged() {
        stagedModelId = ""
        stagedEffort = ""
        stagedAutoApprove = null
    }

    private fun stop() {
        if (taskId.isEmpty() || !controllable) return
        chatSource?.interrupt(computerAgentId, taskId) { ok, message ->
            if (!ok) error = message.ifBlank { "停止失败" }
        }
    }

    private fun requestDelete() {
        if (!canDelete() || deleteBusy) return
        deleteError = ""
        showDeleteConfirm = true
    }

    private fun deleteTask() {
        if (!canDelete() || deleteBusy) return
        deleteBusy = true
        deleteError = ""
        chatSource?.deleteTask(computerAgentId, taskId) { ok, message ->
            deleteBusy = false
            if (!ok) {
                deleteError = message.ifBlank { "删除失败" }
                return@deleteTask
            }
            showDeleteConfirm = false
            setTimeout(pagerId, 300) { close() }
        }
    }

    private fun hideFromList() {
        BusChatLocalHidden.hide(taskId)
        showRemoveConfirm = false
        showToast("已从列表移除")
        setTimeout(pagerId, 260) { close() }
    }

    private fun toggleAnswer(questionId: String, label: String) {
        val question = pendingRequest?.questions?.firstOrNull { it.id == questionId } ?: return
        val current = answerSelection[questionId].orEmpty()
        val next = if (question.multiSelect) {
            if (label in current) current - label else current + label
        } else {
            listOf(label)
        }
        answerSelection = answerSelection + (questionId to next)
    }

    private fun answerRequest(allow: Boolean) {
        val request = pendingRequest ?: return
        if (requestBusy) return
        requestBusy = true
        requestError = ""
        chatSource?.approve(
            agentId = computerAgentId,
            taskId = taskId,
            requestId = request.requestId,
            allow = allow,
            answers = if (allow) answerSelection else emptyMap(),
        ) { ok, message ->
            requestBusy = false
            if (!ok) {
                requestError = message.ifBlank { "发送失败" }
                return@approve
            }
            answeredRequestId = request.requestId
            refreshFeed()
            setTimeout(pagerId, 800) {
                fetchMessages(force = true)
                applyHistory()
            }
        }
    }

    /**
     * 一块缩略图现在的取回状态
     *
     * 第一次遇到就顺手发起下载并立刻记成 Loading，之后的重组只会读表；
     * 终态（离线 / 取不到 / 失败）粘住不自动重试，隧道断着的时候滚动会把同一批图反复送进屏
     */
    private fun artifactStateOf(attachment: DshMessageAttachment): BusChatArtifactState {
        artifactStates[attachment.artifactId]?.let { return it }
        loadArtifact(attachment)
        return BusChatArtifactState(BusChatArtifactStatus.Loading)
    }

    private fun loadArtifact(attachment: DshMessageAttachment) {
        val id = attachment.artifactId
        val source = chatSource
        if (id.isBlank() || source == null || !artifactInFlight.add(id)) return
        artifactStates = artifactStates + (id to BusChatArtifactState(BusChatArtifactStatus.Loading))
        source.fetchArtifact(id, attachment.contentType) { state ->
            artifactInFlight.remove(id)
            if (!alive) return@fetchArtifact
            artifactStates = artifactStates + (id to state)
        }
    }

    private fun fileStateOf(messageId: String, file: DshMessageFileRef): BusChatFileState =
        fileStates[busChatFileKey(messageId, file.path)] ?: BusChatFileState(BusChatFileStatus.Idle)

    /**
     * 点文件卡片（FR-9.4）
     *
     * 已经有 artifactId 就按 FR-9.2 下载再打开；没有就先发 fetchFile，
     * 电脑会先重发带 id 的 taskMessages 再回 ok，所以要再拉一次记录才接得上
     */
    private fun openFileCard(messageId: String, file: DshMessageFileRef) {
        val key = busChatFileKey(messageId, file.path)
        val current = fileStates[key]?.status
        if (current == BusChatFileStatus.Fetching || current == BusChatFileStatus.Downloading) return
        if (file.artifactId.isNotBlank()) {
            downloadFileThenOpen(key, file.artifactId, file.contentType)
            return
        }
        val source = chatSource ?: return
        fileStates = fileStates + (key to BusChatFileState(BusChatFileStatus.Fetching, BUS_CHAT_FILE_FETCHING))
        source.fetchFile(computerAgentId, taskId, messageId, file.path) { ok, error ->
            if (!alive) return@fetchFile
            if (!ok) {
                fileStates = fileStates + (
                    key to BusChatFileState(
                        BusChatFileStatus.Failed,
                        busChatFileFailure(error),
                        error,
                    )
                    )
                return@fetchFile
            }
            fetchMessages(force = true)
            setTimeout(pagerId, fileIdPollMs) { waitForFileArtifactId(messageId, file.path, key, 1) }
        }
    }

    private fun waitForFileArtifactId(messageId: String, path: String, key: String, attempt: Int) {
        if (!alive) return
        val file = fileRefOf(messageId, path)
        if (file != null && file.artifactId.isNotBlank()) {
            downloadFileThenOpen(key, file.artifactId, file.contentType)
            return
        }
        if (attempt >= fileIdMaxAttempts) {
            fileStates = fileStates + (
                key to BusChatFileState(BusChatFileStatus.Failed, BUS_CHAT_FILE_TIMEOUT, "wait_id_timeout")
                )
            return
        }
        fetchMessages(force = true)
        setTimeout(pagerId, fileIdPollMs) { waitForFileArtifactId(messageId, path, key, attempt + 1) }
    }

    private fun downloadFileThenOpen(key: String, artifactId: String, contentType: String) {
        val source = chatSource ?: return
        fileStates = fileStates + (
            key to BusChatFileState(BusChatFileStatus.Downloading, BUS_CHAT_FILE_DOWNLOADING)
            )
        source.fetchArtifact(artifactId, contentType) { state ->
            if (!alive) return@fetchArtifact
            if (state.status != BusChatArtifactStatus.Ready) {
                fileStates = fileStates + (
                    key to BusChatFileState(
                        BusChatFileStatus.Failed,
                        state.label.ifBlank { BUS_CHAT_ARTIFACT_FAILED },
                        state.reason,
                    )
                    )
                return@fetchArtifact
            }
            source.openFile(state.path, contentType) { result ->
                if (!alive) return@openFile
                fileStates = fileStates + if (result == "opened") {
                    key to BusChatFileState(BusChatFileStatus.Idle)
                } else {
                    key to BusChatFileState(BusChatFileStatus.Failed, busChatOpenFailure(result), result)
                }
            }
        }
    }

    /** 打开右上角「产物」整页；先把任务的最新产物列表拉一次，再按 id 预取图片缩略图 */
    private fun openArtifacts() {
        showMenu = false
        if (taskId.isNotEmpty()) {
            val latest = runCatching { chatSource?.artifacts(taskId) }.getOrNull().orEmpty()
            val next = busChatListableArtifacts(latest)
            if (next != artifacts.toList()) {
                artifacts.clear()
                artifacts.addAll(next)
            }
            next.filter { it.kind == "image" }.forEach(::loadArtifactPreview)
        }
        artifactRevision++
        showArtifacts = true
    }

    private fun artifactFileKey(artifact: BusChatArtifact): String = "artifact:${artifact.id}"

    private fun artifactOpenState(artifact: BusChatArtifact): BusChatFileState =
        fileStates[artifactFileKey(artifact)] ?: BusChatFileState(BusChatFileStatus.Idle)

    private fun artifactPreviewState(artifact: BusChatArtifact): BusChatArtifactState =
        artifactStates[artifact.id] ?: BusChatArtifactState(BusChatArtifactStatus.Loading)

    private fun loadArtifactPreview(artifact: BusChatArtifact) {
        if (artifact.kind != "image" || artifact.id.isBlank()) return
        if (artifactStates.containsKey(artifact.id) || !artifactInFlight.add(artifact.id)) return
        val source = chatSource ?: return
        artifactStates = artifactStates + (artifact.id to BusChatArtifactState(BusChatArtifactStatus.Loading))
        artifactRevision++
        source.fetchArtifact(artifact.id, artifact.contentType) { state ->
            artifactInFlight.remove(artifact.id)
            if (!alive) return@fetchArtifact
            artifactStates = artifactStates + (artifact.id to state)
            artifactRevision++
        }
    }

    /** 产物列表点一行：链接走系统浏览器，其余先取回字节再交给系统应用 */
    private fun openArtifact(artifact: BusChatArtifact) {
        if (!busChatArtifactUsable(artifact)) return
        if (artifact.kind == "link") {
            val result = runCatching { bridgeModule?.openUrl(artifact.url) }.getOrNull().orEmpty()
            if (result != "opened") showToast(busChatOpenFailure(result))
            return
        }
        val key = artifactFileKey(artifact)
        val current = fileStates[key]?.status
        if (current == BusChatFileStatus.Fetching || current == BusChatFileStatus.Downloading) return
        val preview = artifactStates[artifact.id]
        if (preview?.status == BusChatArtifactStatus.Ready && preview.path.isNotBlank()) {
            openArtifactPath(key, preview.path, artifact.contentType)
            return
        }
        val source = chatSource ?: return
        fileStates = fileStates + (key to BusChatFileState(BusChatFileStatus.Downloading, BUS_CHAT_FILE_DOWNLOADING))
        artifactRevision++
        source.fetchArtifact(artifact.id, artifact.contentType) { state ->
            if (!alive) return@fetchArtifact
            if (state.status != BusChatArtifactStatus.Ready) {
                fileStates = fileStates + (
                    key to BusChatFileState(
                        BusChatFileStatus.Failed,
                        state.label.ifBlank { BUS_CHAT_ARTIFACT_FAILED },
                        state.reason,
                    )
                    )
                artifactRevision++
                return@fetchArtifact
            }
            openArtifactPath(key, state.path, artifact.contentType)
        }
    }

    private fun openArtifactPath(key: String, path: String, contentType: String) {
        val source = chatSource ?: return
        fileStates = fileStates + (key to BusChatFileState(BusChatFileStatus.Downloading, BUS_CHAT_FILE_DOWNLOADING))
        artifactRevision++
        source.openFile(path, contentType) { result ->
            if (!alive) return@openFile
            fileStates = fileStates + if (result == "opened") {
                key to BusChatFileState(BusChatFileStatus.Idle)
            } else {
                key to BusChatFileState(BusChatFileStatus.Failed, busChatOpenFailure(result), result)
            }
            artifactRevision++
        }
    }

    /** 记录重发之后，这条消息里那个文件现在长什么样（artifactId 可能刚被补上） */
    private fun fileRefOf(messageId: String, path: String): DshMessageFileRef? {
        val row = chatRows.firstOrNull { (it as? BusChatRow.Message)?.message?.id == messageId }
            as? BusChatRow.Message ?: return null
        return row.message.fileRefs.firstOrNull { it.path == path }
    }

    /**
     * 点折叠行：在对话流里就地展开/收起步骤列表（稿子③的中间层）
     *
     * 不再用底部浮层代替这一层——浮层会遮住正文、还跳过用户最常做的一次操作
     */
    private fun toggleToolsExpanded(row: BusChatRow) {
        expandedToolsKey = if (expandedToolsKey == row.key) "" else row.key
    }

    /** 内联列表里点某一行才弹浮层：单步详情（稿子⑤） */
    private fun openStepDetail(row: BusChatRow, index: Int) {
        val tools = when (row) {
            is BusChatRow.ToolGroup -> row.tools
            is BusChatRow.Message -> listOf(row.message)
        }
        if (index !in tools.indices) return
        stepTools.clear()
        stepTools.addAll(tools)
        stepIndex = index
        stepTitle = busChatToolSummary(tools)
        sheet = BusChatSheetKind.STEP_DETAIL
    }

    private fun selectModel(id: String) {
        val model = models.firstOrNull { it.id == id } ?: return
        stagedModelId = model.id
        val current = stagedEffortValue()
        stagedEffort = when {
            model.efforts.isEmpty() -> ""
            model.efforts.any { it == current } -> current
            model.defaultEffort.isNotBlank() -> model.defaultEffort
            else -> model.efforts.first()
        }
        sheet = BusChatSheetKind.NONE
        showToast("下一条消息使用 ${modelLabel()}")
        restoreComposerFocus()
    }

    private fun selectEffort(value: String) {
        stagedEffort = value
        showToast("推理等级 ${busChatEffortLabel(value)}")
    }

    private fun selectWorkspace(path: String) {
        val workspace = workspaces.firstOrNull { it.path == path } ?: return
        if (taskId.isNotEmpty()) {
            // followUp 不带 projectPath，续聊换工作区没有意义，只说明原因
            sheet = BusChatSheetKind.NONE
            showToast("工作区只随新建任务发出，续聊不能更换")
            restoreComposerFocus()
            return
        }
        projectPath = workspace.path
        projectName = workspace.name
        newProjectName = ""
        workspacePicked = true
        sheet = BusChatSheetKind.NONE
        showToast("工作区 ${workspace.name}")
        restoreComposerFocus()
    }

    private fun openNewProjectDialog() {
        if (projectsRoot.isBlank()) {
            showToast("电脑没有配置项目目录")
            return
        }
        sheet = BusChatSheetKind.NONE
        newProjectDraft = ""
        newProjectError = ""
        showNewProjectDialog = true
    }

    private fun submitNewProject() {
        val existing = workspaces.map { it.name }.filter { it.isNotBlank() && it != "不在项目中" }
        val problem = busChatNewProjectError(newProjectDraft, existing)
        if (problem != null) {
            newProjectError = problem
            return
        }
        newProjectName = newProjectDraft.trim()
        projectPath = ""
        projectName = newProjectName
        workspacePicked = true
        showNewProjectDialog = false
        newProjectError = ""
        showToast("将新建项目 $newProjectName")
    }

    private fun selectAgent(key: String) {
        val option = agents.firstOrNull { it.key == key } ?: return
        agentKey = option.key
        agentName = option.name
        sourceKind = option.source
        connectorId = option.connectorId.orEmpty()
        canAutoApprove = option.canAutoApprove
        if (!option.canAutoApprove) {
            stagedAutoApprove = null
            autoApprove = false
        }
        models.clear()
        models.addAll(option.models)
        val first = option.models.firstOrNull()
        stagedModelId = ""
        stagedEffort = ""
        modelId = first?.id.orEmpty()
        effort = first?.defaultEffort.orEmpty().ifBlank {
            first?.efforts?.firstOrNull().orEmpty()
        }
        agentPicked = true
        sheet = BusChatSheetKind.NONE
        skills.clear()
        skillsLoading = false
        skillsError = ""
        skillsRevision++
        showToast("已选择 ${option.name}")
        // agentKey 变化会整页重建，原生输入框文本会丢；回来时把草稿补回去再聚焦
        restoreComposerFocus(restoreText = true)
    }

    private fun openAttach() {
        refreshSkills()
        openSheet(BusChatSheetKind.ATTACH)
    }

    private fun refreshSkills() {
        val source = chatSource ?: return
        skillsLoading = true
        skillsError = ""
        skillsRevision++
        val requestedAgent = agentKey
        val requestedProject = projectPath
        val requestedTask = taskId
        source.refreshSkills(requestedAgent, requestedProject, requestedTask) { ok, error ->
            if (!alive) return@refreshSkills
            if (requestedAgent != agentKey || requestedProject != projectPath || requestedTask != taskId) {
                return@refreshSkills
            }
            if (!ok) {
                skills.clear()
                skillsLoading = false
                skillsError = error.ifBlank { "技能读取失败" }
                skillsRevision++
                return@refreshSkills
            }
            pollSkills(requestedAgent, requestedProject, requestedTask, 0)
        }
    }

    private fun pollSkills(
        requestedAgent: String,
        requestedProject: String,
        requestedTask: String,
        attempt: Int,
    ) {
        if (!alive) return
        if (requestedAgent != agentKey || requestedProject != projectPath || requestedTask != taskId) return
        val list = chatSource?.skills(requestedAgent, requestedProject, requestedTask)
        if (list == null && attempt < 20) {
            setTimeout(pagerId, 250) {
                pollSkills(requestedAgent, requestedProject, requestedTask, attempt + 1)
            }
            return
        }
        skills.clear()
        skills.addAll(list.orEmpty())
        skillsLoading = false
        skillsError = if (list.isNullOrEmpty()) "这个 Agent 没有上报技能" else ""
        skillsRevision++
    }

    private fun openSkillContent(skill: BusChatSkill) {
        if (!skill.hasBody) {
            showToast("这个技能没有正文")
            return
        }
        skillContentTitle = "/${skill.name}"
        skillContentBody = ""
        skillContentError = ""
        skillContentLoading = true
        skillsRevision++
        openSheet(BusChatSheetKind.SKILL_DETAIL)
        val requestedAgent = agentKey
        val requestedProject = projectPath
        val requestedTask = taskId
        chatSource?.readSkill(requestedAgent, requestedProject, requestedTask, skill.id) { content, error ->
            if (!alive) return@readSkill
            if (requestedAgent != agentKey || requestedTask != taskId) return@readSkill
            skillContentLoading = false
            if (content == null) {
                skillContentError = error ?: "读取失败"
            } else {
                skillContentTitle = "/${content.name}"
                skillContentBody = content.body
                skillContentError = ""
            }
            skillsRevision++
        }
    }

    private fun selectSkill(name: String) {
        if (name.isBlank()) return
        val token = "/${name.trimStart('/')} "
        draft = if (draft.isBlank()) token else "${draft.trimEnd()} $token"
        inputView?.setText(draft)
        sheet = BusChatSheetKind.NONE
        restoreComposerFocus()
    }

    private fun close() {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    override fun body(): ViewBuilder {
        return {
            // Agent 变化时整页重建一次：顶栏头像、模型清单和草稿芯片都要跟着换
            // 宽度变化（旋转/分屏）也要重算整页比例
            vbind({
                BusChatPageBinding(
                    dark = this@BusChatPage.themeColors.isDark,
                    agentKey = this@BusChatPage.agentKey,
                    width = this@BusChatPage.pagerData.pageViewWidth,
                )
            }) {
                BusChatTokens.updateDesignScale(this@BusChatPage.pagerData.pageViewWidth)
                val palette = busChatPalette(this@BusChatPage.themeColors)
                val inset = dshTopInset(this@BusChatPage.pagerData.statusBarHeight, this@BusChatPage.pagerData.safeAreaInsets.top)
                val pageWidth = this@BusChatPage.pagerData.pageViewWidth
                val pageHeight = this@BusChatPage.pagerData.pageViewHeight
                View {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        backgroundColor(palette.bgBase)
                    }
                    // 氛围层铺到状态栏和整屏，不能只从页面内容区开始
                    BusChatHeaderGlow(palette)
                    View {
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            paddingTop(inset)
                        }
                        View {
                            attr {
                                flex(1f)
                                flexDirectionColumn()
                            }
                            // 点正文空白处收起键盘；点在卡片/按钮上时由子控件消费，
                            // 不会误收。原生 dispatchTouchEvent 只保护输入条那一条，
                            // 正文这一段靠这个显式事件兜底。
                            event {
                                click { this@BusChatPage.inputView?.blur() }
                            }
                            // 新建会话：空态只放品牌字标，不再挂底部说明
                            // 键盘弹起后字标让位给键盘与草稿芯片，正文区留空
                            vif({
                                this@BusChatPage.taskId.isEmpty() &&
                                    this@BusChatPage.chatRows.isEmpty() &&
                                    this@BusChatPage.keyboardHeight == 0f
                            }) {
                                View {
                                    attr {
                                        flex(1f)
                                        flexDirectionColumn()
                                        paddingTop(busChatPx(56f))
                                    }
                                    BusChatEmptyState(palette)
                                }
                            }
                            vif({
                                this@BusChatPage.taskId.isNotEmpty() &&
                                    this@BusChatPage.chatRows.isEmpty() &&
                                    !this@BusChatPage.loading &&
                                    this@BusChatPage.pendingRequest == null &&
                                    this@BusChatPage.artifacts.isEmpty()
                            }) {
                                View {
                                    attr {
                                        flex(1f)
                                        paddingTop(busChatPx(56f))
                                    }
                                    BusChatEmptyState(palette)
                                }
                            }
                            vif({
                                this@BusChatPage.chatRows.isEmpty() &&
                                    this@BusChatPage.loading
                            }) {
                                View {
                                    attr { flex(1f); allCenter() }
                                    Text {
                                        attr {
                                            text("正在加载记录…")
                                            fontSize(busChatPx(13f))
                                            color(palette.mut)
                                        }
                                    }
                                }
                            }
                            vif({
                                this@BusChatPage.chatRows.isNotEmpty() ||
                                    this@BusChatPage.pendingRequest != null ||
                                    this@BusChatPage.artifacts.isNotEmpty()
                            }) {
                                List {
                                    ref { viewRef -> this@BusChatPage.chatListRef = viewRef }
                                    attr {
                                        flex(1f)
                                        showScrollerIndicator(false)
                                        paddingTop(busChatPx(56f))
                                        paddingLeft(BusChatTokens.pageSide)
                                        paddingRight(BusChatTokens.pageSide)
                                        // 底部留出和渐隐等高的空间，最后一行可以滚到渐隐上方
                                        paddingBottom(busChatPx(44f))
                                        firstContentLoadMaxIndex(30)
                                        preloadViewDistance(pageHeight)
                                    }
                                    event {
                                        scroll { params ->
                                            this@BusChatPage.onListScroll(
                                                offsetY = params.offsetY,
                                                contentHeight = params.contentHeight,
                                                viewHeight = params.viewHeight,
                                            )
                                        }
                                    }
                                    vforLazy(
                                        { this@BusChatPage.feedRows },
                                        maxLoadItem = 80,
                                    ) { item, index, _ ->
                                        View {
                                            attr {
                                                flexDirectionColumn()
                                                width(pageWidth - BusChatTokens.pageSide * 2f)
                                                marginTop(
                                                    busChatFeedGap(
                                                        previous = this@BusChatPage.feedRows.getOrNull(index - 1),
                                                        current = item,
                                                    ),
                                                )
                                            }
                                            when (item) {
                                                is BusChatFeedItem.Hint -> BusChatHint(
                                                    palette,
                                                    "更早的记录在电脑上",
                                                )

                                                is BusChatFeedItem.MessageRow -> BusChatMessageRow(
                                                    row = item.row,
                                                    palette = palette,
                                                    contentWidth = pageWidth - BusChatTokens.pageSide * 2f,
                                                    streaming = {
                                                        this@BusChatPage.isRunning() &&
                                                            item.row is BusChatRow.Message &&
                                                            item.row.message.role == com.kuikagentbus.data.message.DshMessageRole.ASSISTANT &&
                                                            item.row.key == this@BusChatPage.chatRows.lastOrNull()?.key
                                                    },
                                                    expanded = {
                                                        this@BusChatPage.expandedToolsKey == item.row.key
                                                    },
                                                    onToggleTools = {
                                                        this@BusChatPage.toggleToolsExpanded(item.row)
                                                    },
                                                    onSelectStep = { index ->
                                                        this@BusChatPage.openStepDetail(item.row, index)
                                                    },
                                                    artifactState = { attachment ->
                                                        this@BusChatPage.artifactStateOf(attachment)
                                                    },
                                                    fileState = { file ->
                                                        val message = (item.row as? BusChatRow.Message)?.message
                                                        this@BusChatPage.fileStateOf(message?.id.orEmpty(), file)
                                                    },
                                                    onOpenFile = { file ->
                                                        val message = (item.row as? BusChatRow.Message)?.message
                                                        this@BusChatPage.openFileCard(message?.id.orEmpty(), file)
                                                    },
                                                )

                                                is BusChatFeedItem.Streaming ->
                                                    BusChatStreamingLine(palette, "正在执行命令")

                                                is BusChatFeedItem.Approval -> BusChatApprovalCard(
                                                    palette = palette,
                                                    request = item.request,
                                                    busy = { this@BusChatPage.requestBusy },
                                                    error = { this@BusChatPage.requestError },
                                                    questions = item.request.questions,
                                                    selection = this@BusChatPage.answerSelection,
                                                    onToggleOption = { qid, label ->
                                                        this@BusChatPage.toggleAnswer(qid, label)
                                                    },
                                                    onApprove = { this@BusChatPage.answerRequest(true) },
                                                    onDeny = { this@BusChatPage.answerRequest(false) },
                                                )

                                                is BusChatFeedItem.Artifact -> BusChatArtifactCard(
                                                    palette = palette,
                                                    artifact = item.artifact,
                                                    onClick = {
                                                        this@BusChatPage.openArtifact(item.artifact)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            BusChatGlassBackdrop(palette, { this@BusChatPage.listScrolled })
                            BusChatHeader(
                                palette = palette,
                                title = { this@BusChatPage.title },
                                hostName = {
                                    this@BusChatPage.hostName.ifBlank { "电脑" }
                                },
                                projectName = {
                                    this@BusChatPage.projectName.ifBlank {
                                        this@BusChatPage.projectPath.substringAfterLast('/').ifBlank { "默认目录" }
                                    }
                                },
                                agentName = { this@BusChatPage.agentName },
                                avatarAsset = this@BusChatPage.avatarAsset(),
                                avatarOnly = this@BusChatPage.avatarOnly(),
                                dimmed = { this@BusChatPage.sheet != BusChatSheetKind.NONE },
                                onBack = { this@BusChatPage.close() },
                                onMenu = { this@BusChatPage.showMenu = true },
                                onAgentTap = {},
                            )
                            BusChatBottomFade(palette)
                            BusChatBackToBottom(
                                palette = palette,
                                bottom = busChatPx(12f),
                                visible = { !this@BusChatPage.atBottom },
                                onClick = { this@BusChatPage.scrollToBottom() },
                            )
                        }
                        vif({ this@BusChatPage.error.isNotBlank() }) {
                            View {
                                attr {
                                    paddingLeft(BusChatTokens.pageSide)
                                    paddingRight(BusChatTokens.pageSide)
                                    paddingBottom(busChatPx(6f))
                                }
                                Text {
                                    attr {
                                        text(this@BusChatPage.error)
                                        fontSize(busChatPx(11.5f))
                                        lineHeight(busChatPx(11.5f) * 1.5f)
                                        color(palette.bad)
                                        lines(Int.MAX_VALUE)
                                    }
                                }
                            }
                        }
                    }
                    BusChatComposer(
                            palette = palette,
                            draft = { this@BusChatPage.draft },
                            placeholder = {
                                when {
                                    this@BusChatPage.isRunning() -> BUS_CHAT_PLACEHOLDER_RUNNING
                                    this@BusChatPage.taskId.isEmpty() -> "描述你要做的事…"
                                    else -> BUS_CHAT_PLACEHOLDER_IDLE
                                }
                            },
                            running = { this@BusChatPage.isRunning() },
                            readOnly = { this@BusChatPage.isReadOnly() },
                            sending = { this@BusChatPage.sending },
                            modelLabel = { this@BusChatPage.modelLabel() },
                            workspaceLabel = {
                                if (this@BusChatPage.workspacePicked) this@BusChatPage.workspaceLabel()
                                else ""
                            },
                            agentName = {
                                if (this@BusChatPage.agentPicked) this@BusChatPage.agentName else ""
                            },
                            agentAvatar = this@BusChatPage.avatarAsset(),
                            draftMode = { this@BusChatPage.taskId.isEmpty() },
                            keyboardUp = { this@BusChatPage.keyboardHeight > 0f },
                            showAutoApprove = {
                                busChatCanShowAutoApprove(
                                    canAutoApprove = this@BusChatPage.canAutoApprove,
                                    hasProject = this@BusChatPage.projectPath.isNotBlank(),
                                )
                            },
                            autoApprove = { this@BusChatPage.stagedAutoApproveValue() },
                            onDraftChange = { this@BusChatPage.draft = it },
                            onPlus = { this@BusChatPage.openAttach() },
                            onModel = { this@BusChatPage.openSheet(BusChatSheetKind.MODEL) },
                            onAutoApprove = {
                                val next = !this@BusChatPage.stagedAutoApproveValue()
                                if (next && !this@BusChatPage.autoApproveExplained) {
                                    this@BusChatPage.showAutoApproveConfirm = true
                                } else {
                                    this@BusChatPage.stagedAutoApprove = next
                                    this@BusChatPage.showToast(
                                        if (next) "自动批准已开，随下一条消息发出"
                                        else "自动批准已关，随下一条消息发出",
                                    )
                                }
                            },
                            onWorkspace = { this@BusChatPage.openSheet(BusChatSheetKind.WORKSPACE) },
                            onAgentPick = { this@BusChatPage.openSheet(BusChatSheetKind.AGENT) },
                            onSend = { this@BusChatPage.send() },
                            onStop = { this@BusChatPage.stop() },
                            keyboardHeight = { this@BusChatPage.keyboardHeight },
                            onKeyboardHeightChange = { reported ->
                                this@BusChatPage.keyboardHeight =
                                    this@BusChatPage.fullKeyboardInset(reported)
                                val token = ++this@BusChatPage.keyboardScrollToken
                                if (this@BusChatPage.atBottom) {
                                    setTimeout(pagerId, 120) {
                                        val page = this@BusChatPage
                                        if (page.alive && token == page.keyboardScrollToken && page.atBottom) {
                                            page.scrollToBottomNow()
                                        }
                                    }
                                }
                            },
                            onInputFocusChange = { this@BusChatPage.inputFocused = it },
                            onBackgroundTap = { this@BusChatPage.inputView?.blur() },
                            pendingImages = { this@BusChatPage.pendingImages.toList() },
                            onRemovePendingImage = { this@BusChatPage.removePendingImage(it) },
                            onRetryPendingImage = { this@BusChatPage.retryPendingImage(it) },
                            inputRef = { this@BusChatPage.inputView = it },
                        )
                    vif({ this@BusChatPage.showMenu }) {
                        BusChatMenuLayer(
                            palette = palette,
                            top = inset + busChatPx(56f),
                            artifactCount = { this@BusChatPage.artifacts.size },
                            canDelete = { this@BusChatPage.canDelete() },
                            busy = { this@BusChatPage.deleteBusy },
                            onArtifacts = { this@BusChatPage.openArtifacts() },
                            onOpenOnComputer = {
                                this@BusChatPage.showMenu = false
                                this@BusChatPage.showToast("完整视图请在电脑上查看")
                            },
                            onRemove = {
                                this@BusChatPage.showMenu = false
                                this@BusChatPage.showRemoveConfirm = true
                            },
                            onDelete = {
                                this@BusChatPage.showMenu = false
                                this@BusChatPage.requestDelete()
                            },
                            onDismiss = { this@BusChatPage.showMenu = false },
                        )
                    }
                }
                vbind({
                    BusChatSheetBinding(
                        sheet = this@BusChatPage.sheet,
                        stagedEffort = this@BusChatPage.stagedEffortValue(),
                        skillsRevision = this@BusChatPage.skillsRevision,
                        skillsLoading = this@BusChatPage.skillsLoading,
                        skillsError = this@BusChatPage.skillsError,
                        skillContentTitle = this@BusChatPage.skillContentTitle,
                        skillContentBody = this@BusChatPage.skillContentBody,
                        skillContentLoading = this@BusChatPage.skillContentLoading,
                        skillContentError = this@BusChatPage.skillContentError,
                    )
                }) {
                    BusChatSheetHost(
                        kind = this@BusChatPage.sheet,
                        palette = palette,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight,
                        skills = this@BusChatPage.skills.toList(),
                        skillsLoading = this@BusChatPage.skillsLoading,
                        skillsError = this@BusChatPage.skillsError,
                        skillContentTitle = this@BusChatPage.skillContentTitle,
                        skillContentBody = this@BusChatPage.skillContentBody,
                        skillContentLoading = this@BusChatPage.skillContentLoading,
                        skillContentError = this@BusChatPage.skillContentError,
                        models = this@BusChatPage.models.toList(),
                        modelId = this@BusChatPage.modelId,
                        effort = this@BusChatPage.effort,
                        steps = busChatSteps(this@BusChatPage.stepTools.toList()),
                        stepsTitle = this@BusChatPage.stepTitle,
                        stepIndex = this@BusChatPage.stepIndex,
                        workspaces = this@BusChatPage.workspaces.toList(),
                        workspacePath = if (this@BusChatPage.newProjectName.isNotBlank()) "__new__" else this@BusChatPage.projectPath,
                        agents = this@BusChatPage.agents.toList(),
                        agentKey = this@BusChatPage.agentKey,
                        onClose = { this@BusChatPage.sheet = BusChatSheetKind.NONE },
                        showImagePicker = this@BusChatPage.attachmentSupported(),
                        onPickCamera = {
                            this@BusChatPage.sheet = BusChatSheetKind.NONE
                            this@BusChatPage.pickImages("camera")
                        },
                        onPickGallery = {
                            this@BusChatPage.sheet = BusChatSheetKind.NONE
                            this@BusChatPage.pickImages("album")
                        },
                        onPickFile = {
                            this@BusChatPage.sheet = BusChatSheetKind.NONE
                            this@BusChatPage.pickFiles()
                        },
                        onSelectSkill = { this@BusChatPage.selectSkill(it) },
                        onReadSkill = { this@BusChatPage.openSkillContent(it) },
                        onSelectModel = { this@BusChatPage.selectModel(it) },
                        onSelectEffort = { this@BusChatPage.selectEffort(it) },
                        // 单步详情现在是从对话流里的内联列表点进来的，返回=关掉浮层回到列表
                        onBackStep = { this@BusChatPage.sheet = BusChatSheetKind.NONE },
                        onSelectStep = { index ->
                            this@BusChatPage.stepIndex = index
                            this@BusChatPage.sheet = BusChatSheetKind.STEP_DETAIL
                        },
                        onSelectWorkspace = { this@BusChatPage.selectWorkspace(it) },
                        onAddWorkspace = {
                            this@BusChatPage.openNewProjectDialog()
                        },
                        onSelectAgent = { this@BusChatPage.selectAgent(it) },
                    )
                }
                BusChatToast(
                    palette = palette,
                    text = { this@BusChatPage.toast },
                    bottom = { busChatPx(118f) + this@BusChatPage.keyboardHeight },
                )
                DshTextInputDialog(
                    visible = { this@BusChatPage.showNewProjectDialog },
                    title = "新建项目",
                    hint = "文件夹名",
                    value = { this@BusChatPage.newProjectDraft },
                    error = { this@BusChatPage.newProjectError },
                    busy = { false },
                    confirmLabel = "使用这个项目",
                    confirmColor = { this@BusChatPage.themeColors.labelPrimary },
                    onValueChange = {
                        this@BusChatPage.newProjectDraft = it
                        if (this@BusChatPage.newProjectError.isNotEmpty()) {
                            this@BusChatPage.newProjectError = ""
                        }
                    },
                    onCancel = {
                        this@BusChatPage.showNewProjectDialog = false
                        this@BusChatPage.newProjectError = ""
                    },
                    onConfirm = { this@BusChatPage.submitNewProject() },
                    pageViewWidth = pageWidth,
                    verticalOffset = this@BusChatPage.pagerData.statusBarHeight +
                        this@BusChatPage.pagerData.safeAreaInsets.bottom,
                    colors = { this@BusChatPage.themeColors },
                )
                DshSessionConfirmDialog(
                    visible = { this@BusChatPage.showChatConsent },
                    title = "发起任务",
                    message = "请求会由电脑上的 ${this@BusChatPage.agentName.ifBlank { "Agent" }} 发给它的模型服务商。这一段不在端到端加密范围内。继续后，这台电脑上的这个 Agent 不再提示。",
                    busyLabel = "",
                    confirmLabel = "继续",
                    confirmColor = { this@BusChatPage.themeColors.labelPrimary },
                    busy = { false },
                    error = { "" },
                    onCancel = { this@BusChatPage.showChatConsent = false },
                    onConfirm = {
                        this@BusChatPage.showChatConsent = false
                        this@BusChatPage.rememberChatConsent()
                        this@BusChatPage.send()
                    },
                    pageViewWidth = pageWidth,
                    verticalOffset = this@BusChatPage.pagerData.statusBarHeight +
                        this@BusChatPage.pagerData.safeAreaInsets.bottom,
                    colors = { this@BusChatPage.themeColors },
                )
                DshSessionConfirmDialog(
                    visible = { this@BusChatPage.showAutoApproveConfirm },
                    title = "开启自动批准",
                    message = "开启后，电脑上这个项目的命令与改文件会直接放行，不再逐条问你。只对这台电脑上的这个项目生效。",
                    busyLabel = "",
                    confirmLabel = "开启",
                    confirmColor = { this@BusChatPage.themeColors.labelPrimary },
                    busy = { false },
                    error = { "" },
                    onCancel = { this@BusChatPage.showAutoApproveConfirm = false },
                    onConfirm = {
                        this@BusChatPage.showAutoApproveConfirm = false
                        this@BusChatPage.autoApproveExplained = true
                        this@BusChatPage.stagedAutoApprove = true
                        this@BusChatPage.showToast("自动批准已开，随下一条消息发出")
                    },
                    pageViewWidth = pageWidth,
                    verticalOffset = this@BusChatPage.pagerData.statusBarHeight +
                        this@BusChatPage.pagerData.safeAreaInsets.bottom,
                    colors = { this@BusChatPage.themeColors },
                )
                DshSessionConfirmDialog(
                    visible = { this@BusChatPage.showRemoveConfirm },
                    title = "从列表移除",
                    message = "只从本机列表移除，不会删除电脑上的会话",
                    busyLabel = "",
                    confirmLabel = "移除",
                    confirmColor = { this@BusChatPage.themeColors.labelPrimary },
                    busy = { false },
                    error = { "" },
                    onCancel = { this@BusChatPage.showRemoveConfirm = false },
                    onConfirm = { this@BusChatPage.hideFromList() },
                    pageViewWidth = pageWidth,
                    verticalOffset = this@BusChatPage.pagerData.statusBarHeight +
                        this@BusChatPage.pagerData.safeAreaInsets.bottom,
                    colors = { this@BusChatPage.themeColors },
                )
                DshSessionConfirmDialog(
                    visible = { this@BusChatPage.showDeleteConfirm },
                    title = BUS_DELETE_TASK_CONFIRM_TITLE,
                    message = BUS_DELETE_TASK_CONFIRM_MESSAGE,
                    busyLabel = "删除中...",
                    confirmLabel = "永久删除",
                    confirmColor = { this@BusChatPage.themeColors.stateErrorPrimary },
                    busy = { this@BusChatPage.deleteBusy },
                    error = { this@BusChatPage.deleteError },
                    onCancel = {
                        this@BusChatPage.showDeleteConfirm = false
                        this@BusChatPage.deleteError = ""
                    },
                    onConfirm = { this@BusChatPage.deleteTask() },
                    pageViewWidth = pageWidth,
                    verticalOffset = this@BusChatPage.pagerData.statusBarHeight +
                        this@BusChatPage.pagerData.safeAreaInsets.bottom,
                    colors = { this@BusChatPage.themeColors },
                )
                // 右上角「产物」：整页文件列表；状态变化时用 revision 重建这一层
                vif({ this@BusChatPage.showArtifacts }) {
                    vbind({ this@BusChatPage.artifactRevision }) {
                        BusChatArtifactsPage(
                            palette = palette,
                            topInset = inset,
                            artifacts = this@BusChatPage.artifacts.toList(),
                            openState = { this@BusChatPage.artifactOpenState(it) },
                            previewState = { this@BusChatPage.artifactPreviewState(it) },
                            onBack = { this@BusChatPage.showArtifacts = false },
                            onOpen = { this@BusChatPage.openArtifact(it) },
                        )
                    }
                }
                // 浮层开合时同步系统栏明暗；放在最后还是不占位
                vbind({ this@BusChatPage.overlayVisible() }) {
                    this@BusChatPage.syncSystemBarsDim()
                    View { attr { size(0f, 0f) } }
                }
            }
        }
    }
}

private data class BusChatPageBinding(
    val dark: Boolean,
    val agentKey: String,
    val width: Float,
)

private data class BusChatSheetBinding(
    val sheet: BusChatSheetKind,
    val stagedEffort: String,
    val skillsRevision: Int,
    val skillsLoading: Boolean,
    val skillsError: String,
    val skillContentTitle: String,
    val skillContentBody: String,
    val skillContentLoading: Boolean,
    val skillContentError: String,
)