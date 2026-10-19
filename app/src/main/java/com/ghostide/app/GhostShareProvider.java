package com.ghostide.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only content provider backing the Share menu: hands other
 * apps a content:// URI for one workspace file. Paths are
 * workspace-relative and containment-checked (canonical path must
 * stay under the workspace), the same rule the in-app server uses.
 */
public class GhostShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.ghostide.app.files";

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            File root = new File(getContext().getFilesDir(),
                                 "workspace");
            String rel = Uri.decode(uri.getPath());
            if (rel.startsWith("/")) rel = rel.substring(1);
            File f = new File(root, rel);
            String canonRoot = root.getCanonicalPath();
            String canonFile = f.getCanonicalPath();
            if (!canonFile.equals(canonRoot)
                    && !canonFile.startsWith(canonRoot + File.separator))
                throw new FileNotFoundException("outside workspace");
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(
                String.valueOf(e.getMessage()));
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode))
            throw new FileNotFoundException("read only");
        File f = resolve(uri);
        if (!f.isFile()) throw new FileNotFoundException("not found");
        return ParcelFileDescriptor.open(
            f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        String n = name == null ? ""
            : name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[] {
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
            c.addRow(new Object[] { f.getName(), f.length() });
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] args) {
        return 0;
    }
}
