package io.sfrei.tracksearch.clients;

import java.util.ArrayList;
import java.util.List;

public class ClientTestConstants {

    public static final String DEFAULT_SEARCH_KEY = "Ben Böhmer";

    public static final List<String> SEARCH_KEYS;

    static {
        SEARCH_KEYS = new ArrayList<>();
        SEARCH_KEYS.add(DEFAULT_SEARCH_KEY);
        SEARCH_KEYS.add("Paul Kalkbrenner");
        SEARCH_KEYS.add("Einmusik");
        SEARCH_KEYS.add("Mind Against");
        SEARCH_KEYS.add("Adriatique");
        SEARCH_KEYS.add("Fideles");
    }

}
