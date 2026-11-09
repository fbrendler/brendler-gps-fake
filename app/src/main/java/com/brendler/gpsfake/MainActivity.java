package com.brendler.gpsfake;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final String PREFS = "gpsfake";

    private WebView map;
    private EditText search;
    private TextView placeLabel, coords, status;
    private Button btnToggle;
    private ProgressBar progress;

    private boolean hasPoint = false;
    private double curLat, curLng;
    private String curName = null;
    private boolean pendingStart = false;
    private boolean mapReady = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshState();
            ui.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        map = (WebView) findViewById(R.id.map);
        search = (EditText) findViewById(R.id.search);
        placeLabel = (TextView) findViewById(R.id.placeLabel);
        coords = (TextView) findViewById(R.id.coords);
        status = (TextView) findViewById(R.id.status);
        btnToggle = (Button) findViewById(R.id.btnToggle);
        progress = (ProgressBar) findViewById(R.id.progress);

        setupMap();
        status.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String g = MockLocationService.gmsStatus;
                if (MockLocationService.running && g != null && !"OK".equals(g)) showGmsError(g);
            }
        });

        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSearch(); }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.btnCoords).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askCoords(); }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveFavorite(); }
        });
        findViewById(R.id.btnFavs).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFavorites(); }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });

        // restaura o último ponto
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (sp.contains("lat")) {
            curLat = Double.longBitsToDouble(sp.getLong("lat", 0));
            curLng = Double.longBitsToDouble(sp.getLong("lng", 0));
            curName = sp.getString("name", null);
            hasPoint = true;
            updatePanel();
        }

        requestBasicPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    // ---------------------------------------------------------------- mapa

    private void setupMap() {
        WebSettings s = map.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(GeoSearch.USER_AGENT);
        map.addJavascriptInterface(new Bridge(), "Android");
        map.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                mapReady = true;
                if (hasPoint) jsSetPoint(curLat, curLng, 15);
                jsSetActive(MockLocationService.running);
            }
        });
        String html = readAsset("map.html")
                .replace("/*LEAFLET_CSS*/", readAsset("leaflet.css"))
                .replace("/*LEAFLET_JS*/", readAsset("leaflet.js"));
        map.loadDataWithBaseURL("https://brendler-gpsfake.app/", html, "text/html", "UTF-8", null);
    }

    private class Bridge {
        @JavascriptInterface
        public void onMapClick(final double lat, final double lng) {
            ui.post(new Runnable() {
                @Override public void run() { setPoint(lat, lng, null, false); }
            });
        }
    }

    private void jsSetPoint(double lat, double lng, int zoom) {
        if (!mapReady) return;
        map.evaluateJavascript(String.format(Locale.US, "setPoint(%f,%f,%d)", lat, lng, zoom), null);
    }

    private void jsSetActive(boolean a) {
        if (!mapReady) return;
        map.evaluateJavascript("setActive(" + a + ")", null);
    }

    /** Define o ponto escolhido. moveMap=true centraliza o mapa nele. */
    private void setPoint(double lat, double lng, String name, boolean moveMap) {
        curLat = lat;
        curLng = lng;
        curName = name;
        hasPoint = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong("lat", Double.doubleToRawLongBits(lat))
                .putLong("lng", Double.doubleToRawLongBits(lng))
                .putString("name", name)
                .apply();
        updatePanel();
        if (moveMap) jsSetPoint(lat, lng, 16);
        if (MockLocationService.running) sendToService(); // atualiza ao vivo
    }

    private void updatePanel() {
        placeLabel.setText(curName != null && !curName.isEmpty() ? curName : "Ponto escolhido no mapa");
        coords.setText(String.format(Locale.US, "%.6f, %.6f", curLat, curLng));
    }

    // ---------------------------------------------------------------- busca

    private void doSearch() {
        final String q = search.getText().toString();
        if (q.trim().isEmpty()) {
            toast("Digite um endereço, local ou CEP.");
            return;
        }
        hideKeyboard();
        progress.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override public void run() {
                List<GeoSearch.Place> res = null;
                String err = null;
                try {
                    res = GeoSearch.search(q);
                } catch (GeoSearch.SearchException e) {
                    err = e.getMessage();
                }
                final List<GeoSearch.Place> fRes = res;
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        progress.setVisibility(View.GONE);
                        if (fErr != null) { toast(fErr); return; }
                        if (fRes.size() == 1) {
                            GeoSearch.Place p = fRes.get(0);
                            setPoint(p.lat, p.lng, p.name, true);
                        } else {
                            pickResult(fRes);
                        }
                    }
                });
            }
        }).start();
    }

    private void pickResult(final List<GeoSearch.Place> list) {
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) names[i] = list.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle("Escolha o local")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        GeoSearch.Place p = list.get(which);
                        setPoint(p.lat, p.lng, p.name, true);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------------------------------------------------------------- coordenadas manuais

    private void askCoords() {
        final EditText in = new EditText(this);
        in.setHint("-23.561414, -46.655881");
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        if (hasPoint) in.setText(String.format(Locale.US, "%.6f, %.6f", curLat, curLng));
        new AlertDialog.Builder(this)
                .setTitle("Digite as coordenadas")
                .setMessage("Latitude, longitude")
                .setView(wrap(in))
                .setPositiveButton("Ir", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            String[] p = in.getText().toString().trim().split("[,; ]+");
                            double lat = Double.parseDouble(p[0]);
                            double lng = Double.parseDouble(p[1]);
                            if (Math.abs(lat) > 90 || Math.abs(lng) > 180) throw new Exception();
                            setPoint(lat, lng, null, true);
                        } catch (Exception e) {
                            toast("Coordenadas inválidas. Ex.: -23.5614, -46.6558");
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------------------------------------------------------------- favoritos

    private JSONArray loadFavs() {
        try {
            return new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("favs", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void storeFavs(JSONArray a) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("favs", a.toString()).apply();
    }

    private void saveFavorite() {
        if (!hasPoint) { toast("Escolha um local primeiro."); return; }
        final EditText in = new EditText(this);
        in.setHint("Nome do favorito");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        String suggestion = curName != null ? curName : "";
        if (suggestion.length() > 40) suggestion = suggestion.substring(0, 40);
        in.setText(suggestion);
        in.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Salvar nos favoritos")
                .setView(wrap(in))
                .setPositiveButton("Salvar", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = in.getText().toString().trim();
                        if (n.isEmpty()) n = String.format(Locale.US, "%.5f, %.5f", curLat, curLng);
                        try {
                            JSONObject o = new JSONObject();
                            o.put("name", n);
                            o.put("lat", curLat);
                            o.put("lng", curLng);
                            JSONArray a = loadFavs();
                            a.put(o);
                            storeFavs(a);
                            toast("Salvo: " + n);
                        } catch (Exception e) {
                            toast("Erro ao salvar.");
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showFavorites() {
        final JSONArray a = loadFavs();
        if (a.length() == 0) {
            toast("Nenhum favorito ainda. Use ★ Salvar.");
            return;
        }
        final List<String> names = new ArrayList<String>();
        for (int i = 0; i < a.length(); i++) names.add(a.optJSONObject(i).optString("name"));

        final ListView lv = new ListView(this);
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, names);
        lv.setAdapter(ad);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Favoritos")
                .setMessage("Toque para usar • segure para apagar")
                .setView(lv)
                .setNegativeButton("Fechar", null)
                .create();

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                JSONObject o = a.optJSONObject(pos);
                setPoint(o.optDouble("lat"), o.optDouble("lng"), o.optString("name"), true);
                dlg.dismiss();
            }
        });
        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage("Apagar \"" + names.get(pos) + "\"?")
                        .setPositiveButton("Apagar", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                JSONArray n = new JSONArray();
                                for (int i = 0; i < a.length(); i++) if (i != pos) n.put(a.optJSONObject(i));
                                storeFavs(n);
                                dlg.dismiss();
                                showFavorites();
                            }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            }
        });
        dlg.show();
    }

    // ---------------------------------------------------------------- iniciar / parar

    private void toggle() {
        if (MockLocationService.running) {
            startService(new Intent(this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_STOP));
            ui.postDelayed(new Runnable() { @Override public void run() { refreshState(); } }, 300);
            return;
        }
        if (!hasPoint) { toast("Escolha um local no mapa, na busca ou nos favoritos."); return; }

        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestBasicPermissions();
            return;
        }
        if (!isMockAllowed()) {
            showMockHelp();
            return;
        }
        MockLocationService.lastError = null;
        sendToService();
        ui.postDelayed(new Runnable() { @Override public void run() { refreshState(); } }, 600);
    }

    private void sendToService() {
        Intent i = new Intent(this, MockLocationService.class)
                .setAction(MockLocationService.ACTION_START)
                .putExtra(MockLocationService.EXTRA_LAT, curLat)
                .putExtra(MockLocationService.EXTRA_LNG, curLng);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    /** Testa se este app está escolhido como "app de local fictício". */
    private boolean isMockAllowed() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String test = "brendler_test";
        try {
            lm.addTestProvider(test, false, false, false, false, true, true, true, 1, 1);
            lm.removeTestProvider(test);
            return true;
        } catch (SecurityException se) {
            return false;
        } catch (Exception e) {
            return true; // outros erros não são de permissão
        }
    }

    private void showMockHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Ative o app de local fictício")
                .setMessage("Para o GPS Fake funcionar, faça uma vez:\n\n"
                        + "1. Ative as Opções do desenvolvedor (Configurações › Sobre o telefone › toque 7 vezes em \"Número da versão\").\n\n"
                        + "2. Em Opções do desenvolvedor, toque em \"Selecionar app de local fictício\".\n\n"
                        + "3. Escolha \"Brendler GPS Fake\" e volte aqui.")
                .setPositiveButton("Abrir opções", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    }
                })
                .setNegativeButton("Agora não", null)
                .show();
    }

    private void showGmsError(String g) {
        new AlertDialog.Builder(this)
                .setTitle("Google Play Services")
                .setMessage("A simulação no Google (usada pelo Google Maps) não foi aceita.\n\nMotivo: " + g
                        + "\n\nO GPS continua simulado. Tire um print desta mensagem.")
                .setPositiveButton("OK", null)
                .show();
    }

    private boolean lastRunning = false;
    private String lastGms = null;

    private void refreshState() {
        boolean r = MockLocationService.running;
        if (r) {
            btnToggle.setText("Parar GPS Fake");
            btnToggle.setBackgroundResource(R.drawable.btn_stop);
            String g = MockLocationService.gmsStatus;
            status.setText(g == null ? "● Ativando…" : "OK".equals(g) ? "● Ativo" : "● Ativo (só GPS)");
            if (g != null && !"OK".equals(g) && !g.equals(lastGms)) showGmsError(g);
            lastGms = g;
            status.setTextColor(0xFFB45309);
        } else {
            btnToggle.setText("Iniciar GPS Fake");
            btnToggle.setBackgroundResource(R.drawable.btn_primary);
            status.setText("● Desligado");
            status.setTextColor(0xFF6B6680);
        }
        if (r != lastRunning) { jsSetActive(r); lastRunning = r; }

        String err = MockLocationService.lastError;
        if (err != null) {
            MockLocationService.lastError = null;
            if ("MOCK_NOT_ALLOWED".equals(err)) showMockHelp();
            else toast(err);
        }
    }

    // ---------------------------------------------------------------- permissões

    private void requestBasicPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<String>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        if (pendingStart) {
            pendingStart = false;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                toggle();
            } else {
                toast("A permissão de localização é necessária para o GPS Fake.");
            }
        }
    }

    // ---------------------------------------------------------------- utilidades

    private View wrap(View v) {
        FrameLayout f = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        f.setPadding(pad, pad / 2, pad, 0);
        f.addView(v);
        return f;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
