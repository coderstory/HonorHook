package top.honorhook.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.util.concurrent.Executors

/**
 * 相机扫码页（2026-11-03）：CameraX ImageAnalysis 喂 zxing MultiFormatReader，命中即回调。
 * 提示条说明对准 otpauth 二维码；连拍命中去重由 [onResult] 一次性收口（命中即返回不循环）。
 */
@Composable
fun MfaScanScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit, onResult: (List<String>) -> Unit, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInnerPadding)) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("对准 otpauth 二维码（其他验证器导出/网页展示均可）", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
        }
        if (granted) {
            Box(Modifier.fillMaxWidth().height(420.dp), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val executor = Executors.newSingleThreadExecutor()
                        val reader = MultiFormatReader().apply {
                            setHints(mapOf<DecodeHintType, Any>(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                            try {
                                val uri = decodeFrame(reader, proxy)
                                if (uri != null) {
                                    // 命中即收口：可能一屏多码——单帧只报一个，剩余靠再扫
                                    onResult(listOf(uri))
                                    return@setAnalyzer
                                }
                            } catch (_: Exception) {
                            } finally {
                                proxy.close()
                            }
                        }
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            runCatching {
                                val provider = providerFuture.get()
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                    Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) },
                                    analysis,
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                )
            }
        } else {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("需要相机权限才能扫码", fontSize = 14.sp)
            }
        }
    }
}

/** 单帧解码（Y 平面转灰度 LuminanceSource）；无码返回 null。 */
private fun decodeFrame(reader: MultiFormatReader, proxy: ImageProxy): String? {
    val p = proxy.planes[0]
    val bytes = p.buffer.let { b -> val a = ByteArray(b.remaining()); b.get(a); a }
    val src = PlanarYUVLuminanceSource(
        bytes, p.rowStride, proxy.height, 0, 0, proxy.width.coerceAtMost(p.rowStride), proxy.height, false,
    )
    val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(src)))
    reader.reset()
    val text = result.text ?: return null
    return text.takeIf { it.startsWith("otpauth://") }
}
