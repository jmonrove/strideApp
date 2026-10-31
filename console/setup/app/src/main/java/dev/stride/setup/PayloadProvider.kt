package dev.stride.setup

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/** Private provider: individual read grants expose only fixed, verified payloads, never baselines. */
class PayloadProvider:ContentProvider() {
    override fun onCreate()=true
    private fun name(uri:Uri):String {
        require(uri.scheme=="content" && uri.authority=="dev.stride.setup.payload")
        require(uri.pathSegments.size==1 && uri.query==null && uri.fragment==null)
        val name=uri.pathSegments.single();require(Payloads.available(context!!,name));return name
    }
    override fun getType(uri:Uri)=if(name(uri).endsWith(".apk")) "application/vnd.android.package-archive" else "application/gzip"
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        require(mode=="r");return ParcelFileDescriptor.open(Payloads.file(context!!,name(uri)),ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val name=name(uri);val file=Payloads.file(context!!,name)
        val columns=projection ?: arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {addRow(columns.map {when(it) {OpenableColumns.DISPLAY_NAME->name;OpenableColumns.SIZE->file.length();else->null}})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri=throw UnsupportedOperationException("Read only")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException("Read only")
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException("Read only")
}
