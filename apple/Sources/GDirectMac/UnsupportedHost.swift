#if !os(macOS)
import Foundation
@main struct UnsupportedHost { static func main(){print("The GDirectCore library is available here. The app requires macOS 13 or later.")} }
#endif
