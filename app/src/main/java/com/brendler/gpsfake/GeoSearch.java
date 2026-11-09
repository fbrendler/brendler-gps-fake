package com.brendler.gpsfake;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busca de locais:
 *  - Coordenadas digitadas ("-23.56, -46.65")
 *  - CEP brasileiro (ViaCEP -> endereço -> Nominatim/OpenStreetMap)
 *  - Nome / endereço livre (Nominatim/OpenStreetMap)
 * Todos os serviços são gratuitos e não exigem chave.
 */
public class GeoSearch {

    public static final String USER_AGENT =
            "BrendlerGPSFake/1.0 (Android; contato: fabiobrendler@outlook.com)";

    public static class Place {
        public final String name;
        public final double lat, lng;

        public Place(String name, double lat, double lng) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
        }
    }

    public static class SearchException extends Exception {
        public SearchException(String msg) { super(msg); }
    }

    private static final Pattern CEP = Pattern.compile("^\\s*(\\d{5})-?(\\d{3})\\s*$");
    private static final Pattern COORD = Pattern.compile(
            "^\\s*(-?\\d{1,2}(?:[.]\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:[.]\\d+)?)\\s*$");

    public static boolean isCep(String q) {
        return CEP.matcher(q).matches();
    }

    /** Executa a busca (chamar fora da thread principal). */
    public static List<Place> search(String query) throws SearchException {
        String q = query.trim();
        if (q.isEmpty()) throw new SearchException("Digite um endereço, local ou CEP.");

        Matcher mc = COORD.matcher(q);
        if (mc.matches()) {
            double lat = Double.parseDouble(mc.group(1));
            double lng = Double.parseDouble(mc.group(2));
            if (Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
                List<Place> l = new ArrayList<Place>();
                l.add(new Place(String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng), lat, lng));
                return l;
            }
        }

        Matcher m = CEP.matcher(q);
        if (m.matches()) return searchCep(m.group(1) + m.group(2));
        return searchName(q, 8);
    }

    private static List<Place> searchCep(String cep) throws SearchException {
        JSONObject o;
        try {
            o = new JSONObject(get("https://viacep.com.br/ws/" + cep + "/json/"));
        } catch (Exception e) {
            throw new SearchException("Não foi possível consultar o CEP. Verifique a internet.");
        }
        if (o.optBoolean("erro", false)) throw new SearchException("CEP não encontrado.");

        String rua = o.optString("logradouro", "");
        String bairro = o.optString("bairro", "");
        String cidade = o.optString("localidade", "");
        String uf = o.optString("uf", "");
        String cepFmt = cep.substring(0, 5) + "-" + cep.substring(5);

        StringBuilder label = new StringBuilder("CEP " + cepFmt + " – ");
        if (!rua.isEmpty()) label.append(rua).append(", ");
        if (!bairro.isEmpty()) label.append(bairro).append(", ");
        label.append(cidade).append("/").append(uf);

        // Tentativas do mais preciso para o mais genérico
        String[] tries = {
                join(rua, bairro, cidade, uf),
                join(rua, cidade, uf),
                join(bairro, cidade, uf),
                join(cidade, uf)
        };
        for (String t : tries) {
            if (t.isEmpty()) continue;
            try {
                List<Place> r = searchName(t + ", Brasil", 1);
                if (!r.isEmpty()) {
                    Place p = r.get(0);
                    List<Place> out = new ArrayList<Place>();
                    out.add(new Place(label.toString(), p.lat, p.lng));
                    return out;
                }
            } catch (SearchException ignored) { }
        }
        throw new SearchException("Endereço do CEP encontrado, mas sem localização no mapa.");
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static List<Place> searchName(String q, int limit) throws SearchException {
        String body;
        try {
            body = get("https://nominatim.openstreetmap.org/search?format=json&accept-language=pt-BR&limit="
                    + limit + "&q=" + URLEncoder.encode(q, "UTF-8"));
        } catch (Exception e) {
            throw new SearchException("Não foi possível buscar. Verifique a internet.");
        }
        List<Place> out = new ArrayList<Place>();
        try {
            JSONArray a = new JSONArray(body);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Place(o.optString("display_name", q),
                        Double.parseDouble(o.getString("lat")),
                        Double.parseDouble(o.getString("lon"))));
            }
        } catch (Exception e) {
            throw new SearchException("Resposta inválida do serviço de busca.");
        }
        if (out.isEmpty()) throw new SearchException("Nenhum local encontrado.");
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
