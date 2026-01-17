package com.example.pdflibrary

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class FileItem(val name: String, val path: String, val folder: String, val size: Long, val date: Long, val ext: String, val file: File)
enum class SortType { NAME, DATE, SIZE }
enum class FileCategory { PDF, DOC, OTHER }

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F7)) {
                        FileManagerScreen()
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun FileManagerScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var allFiles by remember { mutableStateOf(listOf<FileItem>()) }
        var viewingFile by remember { mutableStateOf<FileItem?>(null) }
        var isScanning by remember { mutableStateOf(false) }

        var currentCategory by remember { mutableStateOf(FileCategory.PDF) }
        var sortType by remember { mutableStateOf(SortType.DATE) }
        var isAscending by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }

        val refreshFiles = {
            scope.launch {
                isScanning = true
                withContext(Dispatchers.IO) { allFiles = queryAllFiles() }
                isScanning = false
            }
        }

        BackHandler(enabled = viewingFile != null || searchQuery.isNotEmpty()) {
            if (viewingFile != null) viewingFile = null
            else if (searchQuery.isNotEmpty()) searchQuery = ""
        }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } else {
                refreshFiles()
            }
        }

        val displayList by remember(allFiles, currentCategory, searchQuery, sortType, isAscending) {
            derivedStateOf {
                val filtered = allFiles.filter { file: FileItem ->
                    val catMatch = when (currentCategory) {
                        FileCategory.PDF -> file.ext == "pdf"
                        FileCategory.DOC -> file.ext in listOf("doc", "docx")
                        FileCategory.OTHER -> file.ext in listOf("ppt", "pptx", "txt")
                    }
                    catMatch && file.name.contains(searchQuery, ignoreCase = true)
                }

                when (sortType) {
                    SortType.NAME -> if (isAscending) filtered.sortedBy { it.name.lowercase() } else filtered.sortedByDescending { it.name.lowercase() }
                    SortType.DATE -> if (isAscending) filtered.sortedBy { it.date } else filtered.sortedByDescending { it.date }
                    SortType.SIZE -> if (isAscending) filtered.sortedBy { it.size } else filtered.sortedByDescending { it.size }
                }
            }
        }

        if (viewingFile != null) {
            InternalReader(viewingFile!!) { viewingFile = null }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar(containerColor = Color.White) {
                        FileCategory.entries.forEach { category ->
                            NavigationBarItem(
                                selected = currentCategory == category,
                                onClick = { currentCategory = category },
                                label = { Text(category.name) },
                                icon = { Icon(if (category == FileCategory.PDF) Icons.Default.PictureAsPdf else Icons.Default.Description, null) }
                            )
                        }
                    }
                },
                topBar = {
                    Column(Modifier.background(Color.White)) {
                        CenterAlignedTopAppBar(title = { Text("HOME", fontWeight = FontWeight.Black) })
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            placeholder = { Text("Tap to search....") },
                            shape = RoundedCornerShape(12.dp)
                        )
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SortType.entries.forEach { type ->
                                FilterChip(
                                    selected = sortType == type,
                                    onClick = {
                                        if (sortType == type) isAscending = !isAscending
                                        else { sortType = type; isAscending = false }
                                    },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(type.name)
                                            if (sortType == type) {
                                                // FIXED: Using Material Icons instead of text symbols
                                                Icon(
                                                    imageVector = if (isAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp).padding(start = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                val listState = rememberLazyListState()
                Box(Modifier.padding(padding).fillMaxSize()) {
                    if (isScanning) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(displayList, key = { _, item -> item.path }) { _, file ->
                                FileRow(
                                    file = file,
                                    onClick = {
                                        if (file.ext == "pdf" || file.ext == "txt") viewingFile = file
                                        else openExternalFile(context, file.file)
                                    },
                                    onLongClick = {
                                        showFileActionDialog(context, file) { refreshFiles() }
                                    }
                                )
                            }
                        }

                        if (displayList.size > 5) {
                            BoxWithConstraints(
                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(45.dp)
                                    .pointerInput(displayList) {
                                        detectDragGestures { change, _ ->
                                            val pos = (change.position.y / size.height).coerceIn(0f, 1f)
                                            scope.launch { listState.scrollToItem((pos * displayList.size).toInt().coerceIn(0, displayList.size - 1)) }
                                        }
                                    }
                            ) {
                                val scrollPercent = if (displayList.isNotEmpty()) listState.firstVisibleItemIndex.toFloat() / displayList.size else 0f
                                Box(Modifier.fillMaxHeight().width(2.dp).background(Color.Black.copy(0.05f)).align(Alignment.Center))
                                Box(Modifier.offset(y = (scrollPercent * (maxHeight.value - 60)).dp).size(8.dp, 60.dp).background(Color.Gray, CircleShape).align(Alignment.TopCenter))
                            }
                        }
                    }
                }
            }
        }
    }

    // NEW: File action dialog for Rename and Delete
    private fun showFileActionDialog(context: android.content.Context, fileItem: FileItem, onUpdate: () -> Unit) {
        val options = arrayOf("Rename", "Delete")
        AlertDialog.Builder(context)
            .setTitle(fileItem.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Rename
                        val input = EditText(context).apply { setText(fileItem.name.substringBeforeLast(".")) }
                        AlertDialog.Builder(context)
                            .setTitle("Rename File")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                val newName = "${input.text}.${fileItem.ext}"
                                val newFile = File(fileItem.file.parent, newName)
                                if (fileItem.file.renameTo(newFile)) onUpdate()
                                else Toast.makeText(context, "Rename failed", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                    1 -> { // Delete
                        AlertDialog.Builder(context)
                            .setTitle("Delete File")
                            .setMessage("Are you sure you want to delete this file?")
                            .setPositiveButton("Delete") { _, _ ->
                                if (fileItem.file.delete()) onUpdate()
                                else Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                }
            }.show()
    }

    @Composable
    fun FileRow(file: FileItem, onClick: () -> Unit, onLongClick: () -> Unit) {
        // FIXED: Using detectTapGestures to support both Click and LongPress
        Box(
            Modifier.fillMaxWidth().height(72.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { onLongClick() }
                    )
                }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column {
                Text(file.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val mbSize = file.size / (1024f * 1024f)
                Text("${file.folder} • ${String.format(Locale.US, "%.2f", mbSize)} MB", fontSize = 12.sp, color = Color.Gray)
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(0.4f))
    }

    @Composable
    fun InternalReader(file: FileItem, onBack: () -> Unit) {
        Column(Modifier.fillMaxSize().background(Color.White)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Text(file.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (file.ext == "pdf") {
                AndroidView(factory = { ctx ->
                    FragmentContainerView(ctx).apply {
                        id = android.view.View.generateViewId()
                        val fragment = androidx.pdf.viewer.fragment.PdfViewerFragment()
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file.file)
                        fragment.arguments = Bundle().apply { putParcelable("documentUri", uri) }
                        supportFragmentManager.beginTransaction().replace(id, fragment).commit()
                    }
                }, modifier = Modifier.fillMaxSize())
            } else {
                val content = remember(file) { try { file.file.readText() } catch (e: Exception) { "Error reading." } }
                Text(content, Modifier.padding(16.dp).verticalScroll(rememberScrollState()))
            }
        }
    }

    private fun openExternalFile(context: android.content.Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {}
    }

    private fun queryAllFiles(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        val projection = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED)
        val selection = "(" + listOf("pdf", "doc", "docx", "ppt", "pptx", "txt").joinToString(" OR ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.$it'" } + ")"
        contentResolver.query(MediaStore.Files.getContentUri("external"), projection, selection, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol) ?: continue
                val f = File(path)
                if (f.exists()) {
                    list.add(FileItem(cursor.getString(nameCol), path, f.parentFile?.name ?: "", cursor.getLong(sizeCol), cursor.getLong(dateCol)*1000, f.extension.lowercase(), f))
                }
            }
        }
        return list
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val LocalOverscrollConfiguration = staticCompositionLocalOf<OverscrollConfiguration?> { null }
@OptIn(ExperimentalFoundationApi::class)
private data class OverscrollConfiguration(val glowColor: Color = Color.Transparent)