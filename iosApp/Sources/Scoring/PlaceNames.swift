import CoreLocation

/// Where the phone is, as a name a person would say: "Amsterdam". Only ever used to
/// prefill a new match's name, so every way this can fail - permission refused,
/// location off, no fix, no network for the geocoder - is the same answer, nil, and
/// the match is named for the time alone.
///
/// Asks for permission itself, and only while iOS has never asked: once the user has
/// answered, a no stays a no and nothing prompts again.
@MainActor
final class PlaceNames: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var authorization: CheckedContinuation<Void, Never>?
    private var location: CheckedContinuation<CLLocation?, Never>?

    override init() {
        super.init()
        manager.delegate = self
        // A town is all the name needs, and the coarsest fix is the fastest one
        // indoors, which is where badminton is played.
        manager.desiredAccuracy = kCLLocationAccuracyReduced
    }

    func current() async -> String? {
        if manager.authorizationStatus == .notDetermined {
            await withCheckedContinuation { continuation in
                authorization = continuation
                manager.requestWhenInUseAuthorization()
            }
        }
        guard [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus) else { return nil }

        let fix = await withCheckedContinuation { (continuation: CheckedContinuation<CLLocation?, Never>) in
            location = continuation
            manager.requestLocation()
        }
        guard let fix else { return nil }

        let placemark = try? await CLGeocoder().reverseGeocodeLocation(fix).first
        // The town, falling back outwards. Never the street: a match name is synced
        // and shared, and a home address has no business in one.
        return [placemark?.locality, placemark?.subAdministrativeArea, placemark?.administrativeArea]
            .compactMap { $0 }
            .first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            // Fires once on creation too, while the status is still undetermined;
            // that one is not an answer.
            guard manager.authorizationStatus != .notDetermined else { return }
            authorization?.resume()
            authorization = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            location?.resume(returning: locations.last)
            location = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            location?.resume(returning: nil)
            location = nil
        }
    }
}
