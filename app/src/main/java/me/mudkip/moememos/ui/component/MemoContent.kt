package me.mudkip.moememos.ui.component

import android.content.Intent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.data.model.MemoRepresentable
import me.mudkip.moememos.data.model.ResourceRepresentable
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ui.page.common.LocalRootNavController
import me.mudkip.moememos.ui.page.common.RouteName
import me.mudkip.moememos.ui.media.MediaViewerActivity
import me.mudkip.moememos.viewmodel.LocalUserState
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.net.URLEncoder
import kotlin.math.ceil

@Composable
fun MemoContent(
    memo: MemoRepresentable,
    previewMode: Boolean = false,
    checkboxChange: (checked: Boolean, startOffset: Int, endOffset: Int) -> Unit = { _, _, _ -> },
    onViewMore: (() -> Unit)? = null,
    selectable: Boolean = false,
    onTagClick: ((String) -> Unit)? = null,
    compact: Boolean = false,
) {
    val rootNavController = LocalRootNavController.current
    val (text, previewed) = remember(memo.content, previewMode, compact) {
        if (previewMode) {
            extractPreviewContent(
                markdownText = memo.content,
                maxLength = if (compact) COMPACT_PREVIEW_MAX_LENGTH else 500
            )
        } else {
            Pair(memo.content, false)
        }
    }
    val handleTagClick = remember(rootNavController, onTagClick) {
        onTagClick ?: { tag ->
            rootNavController.navigate("${RouteName.TAG}/${URLEncoder.encode(tag, "UTF-8")}") {
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Column(
        modifier = Modifier.padding(
            start = if (compact) 10.dp else 15.dp,
            end = if (compact) 10.dp else 15.dp,
            bottom = if (compact) 8.dp else 10.dp
        )
    ) {
        Markdown(
            text,
            imageBaseUrl = LocalUserState.current.host,
            checkboxChange = checkboxChange,
            selectable = selectable,
            onTagClick = handleTagClick
        )

        MemoResourceContent(memo, compact = compact)

        if (previewed && onViewMore != null) {
            Row {
                Text(
                    text = R.string.view_more.string,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable(onClick = onViewMore)
                )
            }
        }
    }
}

private const val PREVIEW_UNBREAKABLE_COST = 100
private const val COMPACT_PREVIEW_MAX_LENGTH = 240
private enum class PreviewAppendKind {
    NONE,
    TEXT,
    UNBREAKABLE
}

fun extractPreviewContent(markdownText: String, maxLength: Int = 500): Pair<String, Boolean> {
    val node = MarkdownParser(GFMFlavourDescriptor()).parse(
        MarkdownElementTypes.MARKDOWN_FILE,
        markdownText,
        true
    )

    val result = StringBuilder()
    var remainingLength = maxLength
    var truncated = false
    var lastAppendKind = PreviewAppendKind.NONE

    fun appendNodeText(child: ASTNode): Boolean {
        if (remainingLength <= 0) {
            truncated = true
            return false
        }
        val content = markdownText.substring(child.startOffset, child.endOffset)
        if (content.isEmpty()) {
            return true
        }
        if (content.length <= remainingLength) {
            result.append(content)
            remainingLength -= content.length
            lastAppendKind = PreviewAppendKind.TEXT
            return true
        }
        result.append(content.take(remainingLength))
        remainingLength = 0
        truncated = true
        lastAppendKind = PreviewAppendKind.TEXT
        return false
    }

    fun appendUnbreakableNode(child: ASTNode): Boolean {
        if (remainingLength < PREVIEW_UNBREAKABLE_COST) {
            truncated = true
            return false
        }
        result.append(markdownText.substring(child.startOffset, child.endOffset))
        remainingLength -= PREVIEW_UNBREAKABLE_COST
        lastAppendKind = PreviewAppendKind.UNBREAKABLE
        return true
    }

    lateinit var extractNodeContent: (ASTNode) -> Boolean
    lateinit var extractBlockContent: (ASTNode) -> Boolean

    extractNodeContent = { child ->
        if (isUnbreakablePreviewNode(child)) {
            appendUnbreakableNode(child)
        } else if (child.children.isEmpty()) {
            appendNodeText(child)
        } else {
            var allSuccess = true
            for (grandChild in child.children) {
                val success = if (isBreakablePreviewBlock(grandChild.type)) {
                    extractBlockContent(grandChild)
                } else {
                    extractNodeContent(grandChild)
                }
                if (!success) {
                    allSuccess = false
                    break
                }
            }
            allSuccess
        }
    }

    extractBlockContent = { child ->
        var allSuccess = true
        val isParagraph = child.type == MarkdownElementTypes.PARAGRAPH
        for (grandChild in child.children) {
            val success = if (isParagraph) {
                extractNodeContent(grandChild)
            } else if (isBreakablePreviewBlock(grandChild.type)) {
                extractBlockContent(grandChild)
            } else if (isPreviewWhitespaceToken(grandChild) || grandChild.children.isEmpty()) {
                appendNodeText(grandChild)
            } else {
                appendUnbreakableNode(grandChild)
            }
            if (!success) {
                allSuccess = false
                break
            }
        }
        allSuccess
    }

    for (child in node.children) {
        val success = if (isBreakablePreviewBlock(child.type)) {
            extractBlockContent(child)
        } else if (isPreviewWhitespaceToken(child)) {
            appendNodeText(child)
        } else {
            appendUnbreakableNode(child)
        }
        if (!success) {
            break
        }
    }

    if (truncated && lastAppendKind == PreviewAppendKind.TEXT) {
        val preview = result.toString().trimEnd()
        val withEllipsis = if (preview.endsWith("…")) preview else "$preview…"
        return Pair(withEllipsis, true)
    }

    return Pair(result.toString(), truncated)
}

private fun isBreakablePreviewBlock(type: IElementType): Boolean {
    return type == MarkdownElementTypes.MARKDOWN_FILE ||
        type == MarkdownElementTypes.PARAGRAPH ||
        type == MarkdownElementTypes.LIST_ITEM ||
        type == MarkdownElementTypes.BLOCK_QUOTE ||
        type == MarkdownElementTypes.ORDERED_LIST ||
        type == MarkdownElementTypes.UNORDERED_LIST
}

private fun isUnbreakablePreviewNode(node: ASTNode): Boolean {
    return node.type == MarkdownElementTypes.IMAGE ||
        node.type == MarkdownElementTypes.CODE_BLOCK ||
        node.type == MarkdownElementTypes.CODE_FENCE ||
        node.type == GFMElementTypes.TABLE ||
        node.type == MarkdownElementTypes.ATX_1 ||
        node.type == MarkdownElementTypes.ATX_2 ||
        node.type == MarkdownElementTypes.ATX_3 ||
        node.type == MarkdownElementTypes.ATX_4 ||
        node.type == MarkdownElementTypes.ATX_5 ||
        node.type == MarkdownElementTypes.ATX_6 ||
        node.type == MarkdownElementTypes.SETEXT_1 ||
        node.type == MarkdownElementTypes.SETEXT_2 ||
        node.type == MarkdownTokenTypes.HORIZONTAL_RULE ||
        node.type == MarkdownElementTypes.LINK_DEFINITION ||
        node.type.toString().contains("HTML")
}

private fun isPreviewWhitespaceToken(node: ASTNode): Boolean {
    return node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE
}

@Composable
fun MemoResourceContent(memo: MemoRepresentable, compact: Boolean = false) {
    val context = LocalContext.current
    val imageList = memo.resources.filter { it.mimeType?.startsWith("image/") == true }
    val imageUrls = remember(imageList) {
        imageList.map { resource -> resource.localUri ?: resource.uri }
    }
    val openImage: (Int) -> Unit = { index ->
        context.startActivity(
            Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(MediaViewerActivity.EXTRA_IMAGE_URLS, imageUrls.toTypedArray())
                putExtra(MediaViewerActivity.EXTRA_INITIAL_INDEX, index)
                putExtra(MediaViewerActivity.EXTRA_CAPTION, memo.content)
            }
        )
    }
    if (imageList.isNotEmpty()) {
        if (compact) {
            CompactMemoImageMosaic(
                images = imageList,
                onOpen = openImage
            )
        } else {
            val cols = 3
            val rows = ceil(imageList.size.toFloat() / cols).toInt()
            for (rowIndex in 0 until rows) {
                Row {
                    for (colIndex in 0 until cols) {
                        val index = rowIndex * cols + colIndex
                        if (index < imageList.size) {
                            Box(modifier = Modifier.fillMaxWidth(1f / (cols - colIndex))) {
                                MemoImage(
                                    url = imageList[index].localUri ?: imageList[index].uri,
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .padding(2.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    resourceIdentifier = (imageList[index] as? ResourceEntity)?.identifier,
                                    onClick = { openImage(index) }
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.fillMaxWidth(1f / cols))
                        }
                    }
                }
            }
        }
    }
    memo.resources.filterNot { it.mimeType?.startsWith("image/") == true }.forEach { resource ->
        Attachment(resource)
    }
}

@Composable
private fun CompactMemoImageMosaic(
    images: List<ResourceRepresentable>,
    onOpen: (Int) -> Unit,
) {
    val visibleCount = minOf(images.size, 4)
    val extraCount = images.size - visibleCount

    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (visibleCount) {
            1 -> {
                CompactAutoImage(
                    image = images[0],
                    onClick = { onOpen(0) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                )
            }
            2 -> {
                CompactImageRow(
                    images = images.take(2),
                    startIndex = 0,
                    extraCount = extraCount,
                    onOpen = onOpen
                )
            }
            3 -> {
                CompactAutoImage(
                    image = images[0],
                    onClick = { onOpen(0) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                )
                CompactImageRow(
                    images = images.subList(1, 3),
                    startIndex = 1,
                    extraCount = extraCount,
                    onOpen = onOpen
                )
            }
            else -> {
                CompactImageRow(
                    images = images.take(2),
                    startIndex = 0,
                    extraCount = 0,
                    onOpen = onOpen
                )
                CompactImageRow(
                    images = images.subList(2, 4),
                    startIndex = 2,
                    extraCount = extraCount,
                    onOpen = onOpen
                )
            }
        }
    }
}

@Composable
private fun CompactImageRow(
    images: List<ResourceRepresentable>,
    startIndex: Int,
    extraCount: Int,
    onOpen: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        images.forEachIndexed { offset, image ->
            val index = startIndex + offset
            val isLast = offset == images.lastIndex
            Box(modifier = Modifier.weight(1f)) {
                CompactAutoImage(
                    image = image,
                    onClick = { onOpen(index) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (isLast && extraCount > 0) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { onOpen(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+$extraCount",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAutoImage(
    image: ResourceRepresentable,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MemoImage(
        url = image.localUri ?: image.uri,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        resourceIdentifier = (image as? ResourceEntity)?.identifier,
        onClick = onClick,
        contentScale = ContentScale.Crop,
        matchIntrinsicAspectRatio = true
    )
}
