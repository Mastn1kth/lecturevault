import Foundation

struct Transcript { let text: String; let timestamped: String }
struct QuizQuestion: Identifiable, Hashable {
    let question: String; let options: [String]; let correctIndex: Int
    var id: String { question }
}

enum APIClient {
    static func transcribe(_ file: URL) async throws -> Transcript {
        let data = try Data(contentsOf: file)
        guard data.count <= 24 * 1024 * 1024 else { throw AppError.message("Часть аудио больше 24 МБ") }
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"lecture.\(file.pathExtension)\"\r\nContent-Type: application/octet-stream\r\n\r\n".data(using: .utf8)!)
        body.append(data); body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        var request = URLRequest(url: URL(string: "\(gateway)/v1/transcribe")!)
        request.httpMethod = "POST"; request.httpBody = body
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let (responseData, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: responseData, service: "Сервер ИИ")
        let json = try JSONSerialization.jsonObject(with: responseData) as? [String: Any] ?? [:]
        let text = (json["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !text.isEmpty else { throw AppError.message("Сервер ИИ вернул пустую расшифровку") }
        let lines = (json["segments"] as? [[String: Any]] ?? []).compactMap { item -> String? in
            guard let value = item["text"] as? String else { return nil }
            return "[\(clock(item["start"] as? Double ?? 0))–\(clock(item["end"] as? Double ?? 0))] \(value.trimmingCharacters(in: .whitespacesAndNewlines))"
        }
        return Transcript(text: text, timestamped: lines.isEmpty ? text : lines.joined(separator: "\n"))
    }

    static func summarize(_ transcript: String, course: String) async throws -> String {
        let payload: [String: Any] = ["task": "summary", "course": course, "transcript": transcript]
        var request = URLRequest(url: URL(string: "\(gateway)/v1/generate")!)
        request.httpMethod = "POST"; request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data, service: "Сервер ИИ")
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let text = (json?["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !text.isEmpty else { throw AppError.message("Сервер ИИ вернул пустой конспект") }
        return text.replacingOccurrences(of: "```markdown", with: "").replacingOccurrences(of: "```", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func generateMiniTest(markdown: String) async throws -> [QuizQuestion] {
        let payload: [String: Any] = ["task": "quiz", "markdown": markdown]
        var request = URLRequest(url: URL(string: "\(gateway)/v1/generate")!)
        request.httpMethod = "POST"; request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await URLSession.shared.data(for: request)
        try validate(response, data: data, service: "Сервер ИИ")
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        var raw = (json?["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        raw = raw.replacingOccurrences(of: "```json", with: "").replacingOccurrences(of: "```", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        let items = try JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] ?? []
        let result = items.compactMap { item -> QuizQuestion? in
            guard let question = item["question"] as? String, let options = item["options"] as? [String], let correct = item["correctIndex"] as? Int,
                  !question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, options.count == 3, (0...2).contains(correct), options.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { return nil }
            return QuizQuestion(question: question, options: options, correctIndex: correct)
        }
        guard result.count == 10 else { throw AppError.message("Сервер ИИ вернул некорректный тест. Попробуйте ещё раз.") }
        return result
    }
    private static let gateway = "https://lecturevault-ai-gateway.aleksandrsimunin828.workers.dev"

    private static func validate(_ response: URLResponse, data: Data, service: String) throws {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            throw AppError.message("\(service): ошибка HTTP \(code)")
        }
    }
    private static func clock(_ seconds: Double) -> String { let value = max(0, Int(seconds)); return String(format: "%02d:%02d:%02d", value / 3600, value % 3600 / 60, value % 60) }
}
