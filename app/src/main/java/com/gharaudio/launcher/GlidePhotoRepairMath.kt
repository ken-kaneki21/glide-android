package com.gharaudio.launcher

import java.util.ArrayDeque

/** Small local pixel repair. No neural inference or semantic reconstruction. */
internal fun repairSmallPhotoMark(
    input: IntArray, width: Int, height: Int, centerX: Int, centerY: Int, radius: Int,
    checkpoint: () -> Unit = {}
): IntArray {
    require(width in 1..2048 && height in 1..2048 && input.size == width * height)
    require(centerX in 0 until width && centerY in 0 until height && radius in 1..32)
    val indices = mutableListOf<Int>()
    val masked = BooleanArray(input.size)
    for (y in maxOf(0,centerY-radius)..minOf(height-1,centerY+radius)) {
        checkpoint()
        for (x in maxOf(0,centerX-radius)..minOf(width-1,centerX+radius)) {
            if ((x-centerX)*(x-centerX)+(y-centerY)*(y-centerY) <= radius*radius) {
                indices.add(y*width+x);masked[y*width+x]=true
            }
        }
    }
    require(indices.size <= maxOf(1,input.size/20)) { "Select a smaller mark" }
    val output=input.copyOf()
    fun neighbors(index:Int):List<Int> {
        val x=index%width;val y=index/width
        return listOfNotNull(if(x>0)index-1 else null,if(x+1<width)index+1 else null,
            if(y>0)index-width else null,if(y+1<height)index+width else null)
    }
    fun average(values:List<Int>):Int {
        var a=0L;var r=0L;var g=0L;var b=0L
        values.forEach { color ->
            val alpha=(color ushr 24).toLong();a+=alpha
            r+=(color ushr 16 and 255)*alpha;g+=(color ushr 8 and 255)*alpha;b+=(color and 255)*alpha
        }
        if(a==0L)return 0
        return ((a/values.size).toInt() shl 24) or ((r/a).toInt() shl 16) or ((g/a).toInt() shl 8) or (b/a).toInt()
    }
    val queue=ArrayDeque<Int>()
    indices.filter { i -> neighbors(i).any { !masked[it] } }.forEach(queue::add)
    while(queue.isNotEmpty()) {
        checkpoint()
        val index=queue.removeFirst()
        if(!masked[index])continue
        val known=neighbors(index).filter { !masked[it] }
        check(known.isNotEmpty())
        output[index]=average(known.map { output[it] });masked[index]=false
        neighbors(index).filter { masked[it] }.forEach(queue::add)
    }
    check(indices.none { masked[it] })
    // A bounded relaxation reduces directional seams. Unmarked pixels stay exact.
    val next=IntArray(indices.size)
    repeat(12) {
        checkpoint()
        indices.forEachIndexed { k,index -> next[k]=average(neighbors(index).map { output[it] }) }
        indices.forEachIndexed { k,index -> output[index]=next[k] }
    }
    return output
}
