package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import org.json.JSONObject;

import java.util.Locale;

final class MlKitLocalization {
    static final String DEFAULT_LOCALE = "auto";

    private final Context context;
    private final String resolvedLocale;

    private MlKitLocalization(Context context, String resolvedLocale) {
        this.context = context;
        this.resolvedLocale = resolvedLocale;
    }

    static MlKitLocalization from(Context context, JSONObject options) {
        Object raw = options == null ? null : options.opt("locale");
        return from(context, raw);
    }

    static MlKitLocalization from(Context context, Object rawLocale) {
        String requested = DEFAULT_LOCALE;
        if (rawLocale != null && rawLocale != JSONObject.NULL) {
            if (!(rawLocale instanceof String)) {
                throw new IllegalArgumentException("locale must be a BCP-47 language tag or auto.");
            }
            requested = ((String) rawLocale).trim();
            if (requested.isEmpty()) {
                throw new IllegalArgumentException("locale must be a BCP-47 language tag or auto.");
            }
        }

        String language;
        if (DEFAULT_LOCALE.equalsIgnoreCase(requested)) {
            LocaleList locales = context.getResources().getConfiguration().getLocales();
            language = locales.isEmpty() ? "en" : locales.get(0).getLanguage();
        } else {
            Locale locale = Locale.forLanguageTag(requested.replace('_', '-'));
            language = locale.getLanguage();
            if (language == null || language.isEmpty()) {
                throw new IllegalArgumentException("locale must be a BCP-47 language tag or auto.");
            }
        }
        language = "es".equalsIgnoreCase(language) ? "es" : "en";

        Configuration configuration = new Configuration(
            context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(language));
        return new MlKitLocalization(
            context.createConfigurationContext(configuration), language);
    }

    String locale() {
        return resolvedLocale;
    }

    String text(String name) {
        int identifier = context.getResources().getIdentifier(
            "mlkit_suite_" + name, "string", context.getPackageName());
        if (identifier == 0) {
            throw new IllegalStateException("Missing ML Kit Suite string resource: " + name);
        }
        return context.getString(identifier);
    }

    String format(String name, Object... arguments) {
        int identifier = context.getResources().getIdentifier(
            "mlkit_suite_" + name, "string", context.getPackageName());
        if (identifier == 0) {
            throw new IllegalStateException("Missing ML Kit Suite string resource: " + name);
        }
        return context.getString(identifier, arguments);
    }
}
