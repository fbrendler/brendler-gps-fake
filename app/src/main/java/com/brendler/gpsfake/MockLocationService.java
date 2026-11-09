package com.brendler.gpsfake;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Serviço em primeiro plano que envia a localização falsa a cada segundo. */
public class MockLocationService extends Service {

    public static final String ACTION_START = "com.brendler.gpsfake.START";
    public static final String ACTION_STOP = "com.brendler.gpsfake.STOP";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";

    private static final String CHANNEL = "gpsfake";
    private static final int NOTIF_ID = 1001;

    public static volatile boolean running = false;
    public static volatile String lastError = null;
    /** Situação da simulação no Google Play Services: null = aguardando, "OK" ou mensagem de erro. */
    public static volatile String gmsStatus = null;
    private int ticks = 0;
    private final Random rnd = new Random();
    private PowerManager.WakeLock wakeLock;
    /** Última vez que o Google aceitou uma posição (para detectar se parou). */
    private volatile long lastFusedOk = 0;

    private double lat, lng;
    private LocationManager lm;
    private FusedLocationProviderClient fused; // Google Play Services (usado pelo Google Maps e outros)
    private final List<String> providers = new ArrayList<String>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            // reafirma o modo simulado do Google a cada 5 s, ou já se ele parou de aceitar
            ticks++;
            if (fused != null && (ticks % 5 == 0
                    || (lastFusedOk > 0 && SystemClock.elapsedRealtime() - lastFusedOk > 3000))) {
                enableFusedMock();
            }
            push();
            if (running) handler.postDelayed(this, 1000);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            return START_NOT_STICKY;
        }
        lat = intent.getDoubleExtra(EXTRA_LAT, 0);
        lng = intent.getDoubleExtra(EXTRA_LNG, 0);

        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            lastError = "Não foi possível iniciar o serviço: " + e.getMessage();
            stopAll();
            return START_NOT_STICKY;
        }

        if (!running) {
            if (!setupProviders()) {
                stopAll();
                return START_NOT_STICKY;
            }
            running = true;
            lastError = null;
            acquireWakeLock();
            handler.post(tick);
        } else {
            push(); // posição alterada enquanto ativo
        }
        return START_STICKY;
    }

    private boolean setupProviders() {
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        providers.clear();
        String[] names = Build.VERSION.SDK_INT >= 31
                ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}
                : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        for (String p : names) {
            try {
                try { lm.removeTestProvider(p); } catch (Exception ignored) { }
                // power=1 (baixo), accuracy=1 (fina)
                lm.addTestProvider(p, false, false, false, false, true, true, true, 1, 1);
                lm.setTestProviderEnabled(p, true);
                providers.add(p);
            } catch (SecurityException se) {
                lastError = "MOCK_NOT_ALLOWED";
                return false;
            } catch (Exception ignored) {
                // provedor não suportado neste aparelho: segue com os demais
            }
        }
        setupFused();
        if (providers.isEmpty() && fused == null) {
            lastError = "Nenhum provedor de localização disponível.";
            return false;
        }
        return true;
    }

    /** Ativa o modo simulado no Google Play Services (Fused Location Provider). */
    private void setupFused() {
        fused = null;
        gmsStatus = null;
        try {
            fused = LocationServices.getFusedLocationProviderClient(this);
            enableFusedMock();
        } catch (Throwable t) {
            // aparelho sem Google Play Services: segue só com o LocationManager
            fused = null;
            gmsStatus = "Google Play Services indisponível (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    private void enableFusedMock() {
        try {
            fused.setMockMode(true)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override public void onSuccess(Void v) {
                            gmsStatus = "OK";
                            push(); // envia a posição falsa imediatamente
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(Exception e) {
                            gmsStatus = e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                    });
        } catch (Throwable t) {
            gmsStatus = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private Location buildLocation(String provider) {
        // pequena variação (~1 m) para parecer um GPS real: posições idênticas
        // podem ser tratadas como "sem atualização" e o ponto fica cinza no Maps
        Location l = new Location(provider);
        l.setLatitude(lat + (rnd.nextDouble() - 0.5) * 0.00002);
        l.setLongitude(lng + (rnd.nextDouble() - 0.5) * 0.00002);
        l.setAltitude(760 + rnd.nextDouble());
        l.setAccuracy(3f + rnd.nextFloat() * 2f);
        l.setSpeed(0f);
        l.setBearing(0f);
        l.setTime(System.currentTimeMillis());
        l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (Build.VERSION.SDK_INT >= 26) {
            l.setVerticalAccuracyMeters(3f);
            l.setSpeedAccuracyMetersPerSecond(0.1f);
            l.setBearingAccuracyDegrees(0.1f);
        }
        return l;
    }

    private void push() {
        if (fused != null) {
            try {
                fused.setMockLocation(buildLocation("fused"))
                        .addOnSuccessListener(new OnSuccessListener<Void>() {
                            @Override public void onSuccess(Void v) {
                                lastFusedOk = SystemClock.elapsedRealtime();
                            }
                        });
            } catch (Throwable ignored) { }
        }
        if (lm == null) return;
        for (String p : providers) {
            try {
                lm.setTestProviderLocation(p, buildLocation(p));
            } catch (SecurityException se) {
                lastError = "MOCK_NOT_ALLOWED";
                stopAll();
                return;
            } catch (Exception ignored) { }
        }
    }

    /** Mantém o envio a cada segundo mesmo com a tela desligada. */
    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrendlerGPSFake:mock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void stopAll() {
        running = false;
        gmsStatus = null;
        lastFusedOk = 0;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        handler.removeCallbacks(tick);
        if (fused != null) {
            try { fused.setMockMode(false); } catch (Throwable ignored) { }
            fused = null;
        }
        if (lm != null) {
            for (String p : providers) {
                try { lm.removeTestProvider(p); } catch (Exception ignored) { }
            }
        }
        providers.clear();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (running) stopAll();
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "GPS Fake ativo",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MockLocationService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Brendler GPS Fake ativo")
                .setContentText(String.format(Locale.US, "%.6f, %.6f", lat, lng))
                .setContentIntent(open)
                .setOngoing(true)
                .setColor(0xFF5B21B6)
                .addAction(new Notification.Action.Builder(0, "Parar", stop).build());
        return b.build();
    }
}
