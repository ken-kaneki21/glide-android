package com.gharaudio.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
internal fun GlidePhotoRepairPreview(bitmap:Bitmap,description:String,selecting:Boolean,
    selected:Pair<Int,Int>?,onSelect:(Pair<Int,Int>)->Unit) {
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        Image(bitmap.asImageBitmap(),description,Modifier.fillMaxSize())
        if(selecting)Canvas(Modifier.fillMaxSize().pointerInput(bitmap.width,bitmap.height) {
            detectTapGestures { point ->
                val scale=minOf(size.width.toFloat()/bitmap.width,size.height.toFloat()/bitmap.height)
                val left=(size.width-bitmap.width*scale)/2;val top=(size.height-bitmap.height*scale)/2
                val x=(point.x-left)/scale;val y=(point.y-top)/scale
                if(x>=0 && x<bitmap.width && y>=0 && y<bitmap.height)onSelect(x.toInt() to y.toInt())
            }
        }) {
            selected?.let { (x,y) ->
                val scale=minOf(size.width/bitmap.width,size.height/bitmap.height)
                val left=(size.width-bitmap.width*scale)/2;val top=(size.height-bitmap.height*scale)/2
                drawCircle(Color(0x88E811A8),minOf(32,maxOf(1,minOf(bitmap.width,bitmap.height)/40))*scale,
                    Offset(left+(x+.5f)*scale,top+(y+.5f)*scale))
            }
        }
    }
}
