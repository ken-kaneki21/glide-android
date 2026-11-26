package com.gharaudio.launcher

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

private val speechMutex=Mutex()
internal data class SpeechResult(val text:String,val seconds:Double,val loadMs:Long,val recognitionMs:Long)
internal fun speechModelDirectory(context:Context)=File(context.filesDir,"glide-speech-$SPEECH_MODEL_SHA")

internal suspend fun transcribeGlideWave(context:Context,uri:Uri):SpeechResult=withContext(Dispatchers.Default) {
    val job=currentCoroutineContext()
    val pcm=withContext(Dispatchers.IO){
        context.contentResolver.openInputStream(uri)?.use{readSpeechWave(it){job.ensureActive()}}
            ?:error("Could not open audio")
    }
    val directory=speechModelDirectory(context)
    withContext(Dispatchers.IO){validateSpeechModel(directory){job.ensureActive()}}
    val start=SystemClock.elapsedRealtime()
    Model(directory.absolutePath).use { model ->
        val loadMs=SystemClock.elapsedRealtime()-start
        job.ensureActive()
        Recognizer(model,16000f).use { recognizer ->
            val text=mutableListOf<String>();val begin=SystemClock.elapsedRealtime()
            var offset=0
            while(offset<pcm.samples.size){
                job.ensureActive()
                val end=minOf(offset+4096,pcm.samples.size)
                val block=pcm.samples.copyOfRange(offset,end)
                if(recognizer.acceptWaveForm(block,block.size)) {
                    JSONObject(recognizer.result).optString("text").takeIf{it.isNotBlank()}?.let(text::add)
                }
                offset=end
            }
            job.ensureActive()
            JSONObject(recognizer.finalResult).optString("text").takeIf{it.isNotBlank()}?.let(text::add)
            SpeechResult(text.joinToString(" "),pcm.seconds,loadMs,SystemClock.elapsedRealtime()-begin)
        }
    }
}

@Composable
internal fun GlideOfflineSpeech(onClose:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var status by remember{mutableStateOf("English transcription runs on this phone. Import the exact Vosk small-en-us-0.15 ZIP, then choose a local PCM16 WAV of at most 30 seconds.")}
    var transcript by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var pickerPending by remember{mutableStateOf(false)}
    var work by remember{mutableStateOf<Job?>(null)}
    var modelReady by remember{mutableStateOf(File(speechModelDirectory(context),"verified-files.txt").isFile)}
    fun launchSpeech(operation:suspend ()->Unit){
        if(busy || pickerPending)return
        busy=true
        work=scope.launch {
            if(!speechMutex.tryLock()){busy=false;status="Another transcription operation is finishing.";return@launch}
            try { operation() }
            catch(cancelled:CancellationException){status="Cancelled. The previous transcript is unchanged.";throw cancelled}
            catch(_:LinkageError){status="The native recognizer could not load on this device."}
            catch(failure:Exception){status=failure.message?.take(180) ?: "Could not process this file. The previous transcript is unchanged."}
            finally {speechMutex.unlock();busy=false;work=null;modelReady=File(speechModelDirectory(context),"verified-files.txt").isFile}
        }
    }
    val modelPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri ->
        pickerPending=false
        if(uri!=null)launchSpeech {
            status="Verifying and importing model…"
            val job=currentCoroutineContext()
            withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use{
                importSpeechModel(it,speechModelDirectory(context)){job.ensureActive()}
            } ?: error("Could not open model archive")}
            status="Verified English model imported locally. No model is loaded until transcription starts."
        }
    }
    val audioPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri ->
        pickerPending=false
        if(uri!=null)launchSpeech {
            status="Transcribing on this phone…"
            val result=transcribeGlideWave(context,uri)
            currentCoroutineContext().ensureActive()
            transcript=result.text
            status=if(result.text.isBlank())"No speech recognized."
                else "${"%.1f".format(result.seconds)} s audio; model load ${result.loadMs} ms; recognition ${result.recognitionMs} ms."
        }
    }
    AlertDialog(onDismissRequest={work?.cancel();onClose()},title={Text("Offline transcription")},
        text={Column(Modifier.verticalScroll(rememberScrollState())){
            Text(status)
            Text("PCM16 WAV only: mono/stereo, 8–48 kHz, at most 30 seconds and 8 MiB. Resampled to 16 kHz. AAC/MP3 and other containers are not supported in this preview.")
            Text("No microphone access or audio focus. Transcripts are kept only in this dialog and discarded when closed. Recognition may be inaccurate.")
            if(transcript.isNotEmpty())SelectionContainer{Text(transcript)}
            TextButton(enabled=!busy && !pickerPending,onClick={pickerPending=true;modelPicker.launch("application/zip")}){Text("Import verified model")}
            TextButton(enabled=modelReady && !busy && !pickerPending,onClick={pickerPending=true;audioPicker.launch("audio/*")}){Text("Choose WAV file")}
            if(busy)TextButton(onClick={work?.cancel()}){Text("Cancel")}
        }},confirmButton={TextButton(onClick={work?.cancel();onClose()}){Text("Close")}})
}
