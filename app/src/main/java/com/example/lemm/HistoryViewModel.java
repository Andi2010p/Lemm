package com.example.lemm;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MVVM ViewModel for the History screen: loads records through {@link HistoryRepository} off the main
 * thread and exposes them as {@link LiveData} so the Activity just observes (survives rotation, no
 * direct DB calls in the UI).
 */
public class HistoryViewModel extends AndroidViewModel {
    private final HistoryRepository repository;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<HistoryRecord>> items = new MutableLiveData<>();

    public HistoryViewModel(@NonNull Application app) {
        super(app);
        repository = ServiceLocator.historyRepository(app);
    }

    public LiveData<List<HistoryRecord>> getItems() { return items; }

    /** Asynchronously (re)loads the list for the current user/tab and publishes it to observers. */
    public void load(String user, boolean solutions) {
        io.execute(() -> items.postValue(repository.load(user, solutions)));
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        io.shutdown();
    }
}
