package com.imagepicker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.module.annotations.ReactModule;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.ContentResolver;
import android.os.ParcelFileDescriptor;
import static com.imagepicker.Utils.*;

public class ImagePickerModuleImpl implements ActivityEventListener {
    static final String NAME = "ImagePicker";

    public static final int REQUEST_LAUNCH_IMAGE_CAPTURE = 13001;
    public static final int REQUEST_LAUNCH_VIDEO_CAPTURE = 13002;
    public static final int REQUEST_LAUNCH_LIBRARY = 13003;
    public static final int REQUEST_LAUNCH_MIXED_CAPTURE = 13004;

    private Uri fileUri;
    private Uri captureImageUri;
    private Uri captureVideoUri;
    private ReactApplicationContext reactContext;
    Callback callback;
    Options options;
    Uri cameraCaptureURI;

    private long getFileSizeFromUri(Uri uri) {
        ContentResolver contentResolver = reactContext.getContentResolver();
        try (ParcelFileDescriptor fileDescriptor = contentResolver.openFileDescriptor(uri, "r")) {
            if (fileDescriptor != null) {
                return fileDescriptor.getStatSize();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public ImagePickerModuleImpl(ReactApplicationContext reactContext) {
        this.reactContext = reactContext;
        this.reactContext.addActivityEventListener(this);
    }

    public void launchCamera(final ReadableMap options, final Callback callback) {
        if (!isCameraAvailable(reactContext)) {
            callback.invoke(getErrorMap(errCameraUnavailable, null));
            return;
        }
        final Activity currentActivity = this.reactContext.getCurrentActivity();
        if (currentActivity == null) {
            callback.invoke(getErrorMap(errOthers, "Activity error"));
            return;
        }
        if (!isCameraPermissionFulfilled(reactContext, currentActivity)) {
            callback.invoke(getErrorMap(errOthers, cameraPermissionDescription));
            return;
        }
        this.callback = callback;
        this.options = new Options(options);
        if (this.options.saveToPhotos && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasPermission(currentActivity)) {
            callback.invoke(getErrorMap(errPermission, null));
            return;
        }
        Intent cameraIntent;
        int requestCode;
        if (this.options.mediaType.equals("mixed")) {
            File imageFile = createFile(reactContext, "jpg");
            File videoFile = createFile(reactContext, "mp4");
            captureImageUri = createUri(imageFile, reactContext);
            captureVideoUri = createUri(videoFile, reactContext);
            Intent imageIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            imageIntent.putExtra(MediaStore.EXTRA_OUTPUT, captureImageUri);
            if (this.options.useFrontCamera) {
                setFrontCamera(imageIntent);
            }
            imageIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Intent videoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            videoIntent.putExtra(MediaStore.EXTRA_OUTPUT, captureVideoUri);
            videoIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, this.options.videoQuality);
            if (this.options.durationLimit > 0) {
                videoIntent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, this.options.durationLimit);
            }
            if (this.options.useFrontCamera) {
                setFrontCamera(videoIntent);
            }
            videoIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Intent chooserIntent = Intent.createChooser(imageIntent, "Choose capture mode");
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{videoIntent});
            cameraIntent = chooserIntent;
            requestCode = REQUEST_LAUNCH_MIXED_CAPTURE;
        } else if (this.options.mediaType.equals(mediaTypeVideo)) {
            requestCode = REQUEST_LAUNCH_VIDEO_CAPTURE;
            cameraIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            File file = createFile(reactContext, "mp4");
            captureVideoUri = createUri(file, reactContext);
            captureImageUri = null;
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, captureVideoUri);
            cameraIntent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, this.options.videoQuality);
            if (this.options.durationLimit > 0) {
                cameraIntent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, this.options.durationLimit);
            }
        } else {
            requestCode = REQUEST_LAUNCH_IMAGE_CAPTURE;
            cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            File file = createFile(reactContext, "jpg");
            captureImageUri = createUri(file, reactContext);
            captureVideoUri = null;
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, captureImageUri);
        }
        if (!this.options.mediaType.equals("mixed") && this.options.useFrontCamera) {
            setFrontCamera(cameraIntent);
        }
        if (!this.options.mediaType.equals("mixed")) {
            cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        try {
            currentActivity.startActivityForResult(cameraIntent, requestCode);
        } catch (ActivityNotFoundException e) {
            callback.invoke(getErrorMap(errOthers, e.getMessage()));
            this.callback = null;
        }
    }

    public void launchImageLibrary(final ReadableMap options, final Callback callback) {
        final Activity currentActivity = this.reactContext.getCurrentActivity();
        if (currentActivity == null) {
            callback.invoke(getErrorMap(errOthers, "Activity error"));
            return;
        }
        this.callback = callback;
        this.options = new Options(options);
        int requestCode = REQUEST_LAUNCH_LIBRARY;
        Intent libraryIntent;
        int selectionLimit = this.options.selectionLimit;
        boolean isSingleSelect = selectionLimit == 1;
        boolean isPhoto = this.options.mediaType.equals(mediaTypePhoto);
        boolean isVideo = this.options.mediaType.equals(mediaTypeVideo);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (isSingleSelect && (isPhoto || isVideo)) {
                libraryIntent = new Intent(Intent.ACTION_PICK);
            } else {
                libraryIntent = new Intent(Intent.ACTION_GET_CONTENT);
                libraryIntent.addCategory(Intent.CATEGORY_OPENABLE);
            }
        } else {
            libraryIntent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        }
        if (!isSingleSelect) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                libraryIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            } else {
                if (selectionLimit != 1) {
                    int maxNum = selectionLimit;
                    if (selectionLimit == 0) maxNum = MediaStore.getPickImagesMaxLimit();
                    libraryIntent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxNum);
                }
            }
        }
        if (isPhoto) {
            libraryIntent.setType("image/*");
        } else if (isVideo) {
            libraryIntent.setType("video/*");
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            libraryIntent.setType("*/*");
            libraryIntent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        }
        try {
            currentActivity.startActivityForResult(libraryIntent, requestCode);
        } catch (ActivityNotFoundException e) {
            callback.invoke(getErrorMap(errOthers, e.getMessage()));
            this.callback = null;
        }
    }

    void onAssetsObtained(List<Uri> fileUris) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                callback.invoke(getResponseMap(fileUris, options, reactContext));
            } catch (RuntimeException exception) {
                callback.invoke(getErrorMap(errOthers, exception.getMessage()));
            } finally {
                callback = null;
            }
        });
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (!isValidRequestCode(requestCode) || this.callback == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == REQUEST_LAUNCH_IMAGE_CAPTURE) {
                deleteFile(captureImageUri);
            } else if (requestCode == REQUEST_LAUNCH_VIDEO_CAPTURE) {
                deleteFile(captureVideoUri);
            } else if (requestCode == REQUEST_LAUNCH_MIXED_CAPTURE) {
                deleteFile(captureImageUri);
                deleteFile(captureVideoUri);
            }
            try {
                callback.invoke(getCancelMap());
                return;
            } catch (RuntimeException exception) {
                callback.invoke(getErrorMap(errOthers, exception.getMessage()));
            } finally {
                callback = null;
            }
        }
        switch (requestCode) {
            case REQUEST_LAUNCH_IMAGE_CAPTURE:
                if (options.saveToPhotos) {
                    saveToPublicDirectory(captureImageUri, reactContext, "photo");
                }
                onAssetsObtained(Collections.singletonList(captureImageUri));
                break;
            case REQUEST_LAUNCH_VIDEO_CAPTURE:
                if (options.saveToPhotos) {
                    saveToPublicDirectory(captureVideoUri, reactContext, "video");
                }
                onAssetsObtained(Collections.singletonList(captureVideoUri));
                break;
            case REQUEST_LAUNCH_MIXED_CAPTURE:
                long imageSize = getFileSizeFromUri(captureImageUri);
                long videoSize = getFileSizeFromUri(captureVideoUri);
                if (imageSize > 0) {
                    if (options.saveToPhotos) {
                        saveToPublicDirectory(captureImageUri, reactContext, "photo");
                    }
                    onAssetsObtained(Collections.singletonList(captureImageUri));
                    deleteFile(captureVideoUri);
                } else if (videoSize > 0) {
                    if (options.saveToPhotos) {
                        saveToPublicDirectory(captureVideoUri, reactContext, "video");
                    }
                    onAssetsObtained(Collections.singletonList(captureVideoUri));
                    deleteFile(captureImageUri);
                } else {
                    callback.invoke(getErrorMap(errOthers, "No file was created"));
                    deleteFile(captureImageUri);
                    deleteFile(captureVideoUri);
                }
                break;
            case REQUEST_LAUNCH_LIBRARY:
                onAssetsObtained(collectUrisFromData(data));
                break;
        }
    }

    @Override
    public void onNewIntent(Intent intent) { }
}
