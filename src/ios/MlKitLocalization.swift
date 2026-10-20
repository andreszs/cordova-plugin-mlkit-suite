import Foundation

final class MlKitLocalization {
    static let supportedLocales = ["en", "es"]

    let locale: String
    private let localizedBundle: Bundle?
    private let englishBundle: Bundle?

    init(dictionary: [String: Any] = [:]) throws {
        let requested: String
        if let raw = dictionary["locale"], !(raw is NSNull) {
            guard let value = raw as? String,
                  !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw MlKitSuiteError.invalidArgument(
                    "locale must be a BCP-47 language tag or auto."
                )
            }
            requested = value.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            requested = "auto"
        }

        let language: String
        if requested.caseInsensitiveCompare("auto") == .orderedSame {
            let candidates = Bundle.preferredLocalizations(
                from: Self.supportedLocales,
                forPreferences: Locale.preferredLanguages
            )
            language = candidates.first ?? "en"
        } else {
            let parsed = Locale(identifier: requested.replacingOccurrences(of: "-", with: "_"))
            guard let code = parsed.languageCode, !code.isEmpty else {
                throw MlKitSuiteError.invalidArgument(
                    "locale must be a BCP-47 language tag or auto."
                )
            }
            language = code.lowercased() == "es" ? "es" : "en"
        }
        locale = language

        let container = Bundle(for: MlKitSuitePlugin.self)
        let resourcesURL = container.url(
            forResource: "MlKitSuiteResources", withExtension: "bundle"
        ) ?? Bundle.main.url(forResource: "MlKitSuiteResources", withExtension: "bundle")
        let resources = resourcesURL.flatMap(Bundle.init(url:))
        localizedBundle = resources?.path(forResource: language, ofType: "lproj")
            .flatMap(Bundle.init(path:))
        englishBundle = resources?.path(forResource: "en", ofType: "lproj")
            .flatMap(Bundle.init(path:))
    }

    func text(_ key: String) -> String {
        let value = localizedBundle?.localizedString(forKey: key, value: nil, table: nil) ?? key
        if value != key { return value }
        return englishBundle?.localizedString(forKey: key, value: key, table: nil) ?? key
    }

    func format(_ key: String, _ arguments: CVarArg...) -> String {
        return String(format: text(key), locale: Locale(identifier: locale), arguments: arguments)
    }
}
