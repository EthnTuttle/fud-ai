import Foundation

/// Lowercased word lists precomputed once per exercise so library search does not
/// re-tokenize every item on each keystroke.
struct ExerciseSearchIndex: Hashable, Sendable {
    let nameWords: [String]
    /// The name words joined without separators, starting at each word in turn
    /// ("cablechestpress", "chestpress", "press"), so phrase matches stay word-aligned
    /// and "pushup" / "push up" / "push-up" all compare equal.
    let nameSuffixes: [String]
    /// Equipment, category, and primary-muscle words. Instructions and secondary muscles
    /// are deliberately excluded: they mention many unrelated words and made searches
    /// return non-matching items.
    let metadataWords: [String]

    nonisolated init(name: String, metadata: [String]) {
        let nameWords = ExerciseSearchMatcher.words(in: name)
        self.nameWords = nameWords
        self.nameSuffixes = nameWords.indices.map { nameWords[$0...].joined() }
        self.metadataWords = metadata.flatMap { ExerciseSearchMatcher.words(in: $0) }
    }
}

/// Tokenized AND search for the exercise library, with optional query aliases and
/// relevance ranking: exact name > name prefix > phrase inside the name >
/// every word in the name > match that needs equipment/category/primary-muscle words.
enum ExerciseSearchMatcher {
    /// Maps common multi-word phrases to catalog exercise IDs.
    nonisolated static let aliases: [String: String] = [
        "cable pushdown": "Triceps_Pushdown",
        "triceps cable pushdown": "Triceps_Pushdown",
        "tricep cable pushdown": "Triceps_Pushdown",
        "tricep pushdown": "Triceps_Pushdown",
    ]

    /// Lowercased runs of letters and digits; punctuation and whitespace separate words.
    nonisolated static func words(in text: String) -> [String] {
        text
            .lowercased()
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .map(String.init)
    }

    nonisolated static func tokens(from query: String) -> [String] {
        words(in: query)
    }

    /// Relevance rank for `query` (lower is better), or nil when the item does not match.
    /// An empty query matches everything with rank 0.
    nonisolated static func relevance(of index: ExerciseSearchIndex, query: String, exerciseID: String) -> Int? {
        let tokens = tokens(from: query)
        guard !tokens.isEmpty else { return 0 }

        let compactQuery = tokens.joined()
        let compactName = index.nameSuffixes.first ?? ""
        if aliases[tokens.joined(separator: " ")] == exerciseID || compactName == compactQuery {
            return 0
        }
        if compactName.hasPrefix(compactQuery) {
            return 1
        }
        if index.nameSuffixes.dropFirst().contains(where: { $0.hasPrefix(compactQuery) }) {
            return 2
        }
        if tokens.allSatisfy({ token in index.nameWords.contains { word($0, matches: token) } }) {
            return 3
        }
        let allWords = index.nameWords + index.metadataWords
        if tokens.allSatisfy({ token in allWords.contains { word($0, matches: token) } }) {
            return 4
        }
        return nil
    }

    nonisolated static func relevance(of item: ExerciseLibraryItem, query: String) -> Int? {
        relevance(of: item.searchIndex, query: query, exerciseID: item.id)
    }

    nonisolated static func matches(_ item: ExerciseLibraryItem, query: String) -> Bool {
        relevance(of: item, query: query) != nil
    }

    /// Word-prefix match, so "tricep" finds "Triceps" but "press" no longer matches
    /// inside unrelated words. Non-ASCII tokens also match inside a word because
    /// scripts such as Japanese or Chinese do not separate words with spaces.
    nonisolated private static func word(_ word: String, matches token: String) -> Bool {
        word.hasPrefix(token) || (!token.allSatisfy(\.isASCII) && word.contains(token))
    }
}
