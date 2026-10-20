import SwiftUI
import AVFoundation
import MobileVLCKit
import UniformTypeIdentifiers
import UIKit

struct VLCPlaybackView: View {
    @EnvironmentObject private var telegram: TelegramClient
    @EnvironmentObject private var rangeServer: TelegramRangeServer
    @Environment(\.scenePhase) private var phase
    @StateObject private var playback: PlaybackController
    @ObservedObject private var orientation = PlayerOrientation.shared
    @State private var controlsVisible = true
    @State private var locked = false
    @State private var importing = false
    @State private var importAudio = false
    @State private var panel: String?
    @State private var trackTab = "Subtitles"
    @State private var closing = false
    @State private var pressingControl = false
    @State private var interaction = UUID()
    @State private var actionFeedback = ""
    @State private var feedbackToken = UUID()
    @State private var message = ""
    @State private var suspended = false
    private let onClose: () -> Void
    private let spice = Color(red: 1, green: 0.43, blue: 0.12)

    init(selection: PlaybackSelection, library: LibraryStore, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _playback = StateObject(wrappedValue: PlaybackController(selection: selection, library: library))
    }
    private var streaming: Bool { playback.selection.url.host == "127.0.0.1" }
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black.ignoresSafeArea()
                VLCVideoSurface(player: playback.player)
                    .allowsHitTesting(false)
                    .modifier(VideoEdges(expanded: playback.edgeToEdge))
                    .onAppear { updateAspect(geometry) }
                    .onChange(of: playback.aspect) { _ in updateAspect(geometry) }
                    .onChange(of: geometry.size) { _ in updateAspect(geometry) }
                    .onChange(of: playback.edgeToEdge) { _ in updateAspect(geometry) }
                if !controlsVisible && !locked && panel == nil { videoGestures }
                if playback.isOpening || playback.isBuffering {
                    VStack(spacing: 12) {
                        ProgressView().tint(spice).scaleEffect(1.2)
                        Text(playback.isOpening ? "Opening media…" : "Buffering…").font(.caption.weight(.medium))
                    }.padding(20).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 16))
                        .allowsHitTesting(false)
                }
                if controlsVisible && !locked {
                    LinearGradient(colors: [.black.opacity(0.8), .clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom).allowsHitTesting(false)
                    VStack(spacing: 8) {
                        topBar
                        videoGestures.frame(maxHeight: .infinity)
                        if !playback.error.isEmpty {
                            notice(playback.error)
                            if streaming {
                                Button("Download to try local playback") {
                                    playback.player.pause(); rangeServer.stop()
                                    telegram.download(playback.selection.video)
                                    closePlayer()
                                }.buttonStyle(.borderedProminent).tint(spice)
                            }
                        }
                        if !message.isEmpty { notice(message) }
                        if !playback.resumeWarning.isEmpty {
                            notice(playback.resumeWarning)
                            Button("Start from beginning") { playback.startOver() }.font(.caption.bold())
                        }
                        timeline
                        if geometry.size.width > 600 {
                            HStack(spacing: 16) {
                                trackTool
                                rotateTool
                                Spacer(minLength: 0)
                                transport
                                Spacer(minLength: 0)
                                pictureTool
                                moreTool
                            }
                        } else {
                            transport.frame(maxWidth: .infinity)
                            tools
                        }
                    }.padding(.horizontal, geometry.size.width > 700 ? 32 : 14).padding(.vertical, 12)
                }
                if !actionFeedback.isEmpty {
                    Text(actionFeedback).font(.subheadline.weight(.semibold)).padding(12)
                        .background(.black.opacity(0.75), in: Capsule()).allowsHitTesting(false)
                }
                if locked {
                    VStack { Spacer(); Button { locked = false; controlsVisible = true } label: {
                        Label("Unlock", systemImage: "lock.open").padding(14).background(.ultraThinMaterial, in: Capsule())
                    }.padding() }
                }
                if let panel {
                    Color.clear.contentShape(Rectangle()).onTapGesture { self.panel = nil }
                    VStack {
                        Spacer(minLength: 0)
                        options(panel, maxHeight: max(88, geometry.size.height * (geometry.size.width > geometry.size.height ? 0.6 : 0.32)))
                    }.frame(maxWidth: geometry.size.width > geometry.size.height ? 340 : 420)
                        .padding(12).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: geometry.size.width > geometry.size.height ? .bottomTrailing : .bottom)
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.black.ignoresSafeArea())
        .foregroundStyle(.white)
        .preferredColorScheme(.dark)
        .buttonStyle(CinespiceButtonStyle { pressed in
            pressingControl = pressed; interaction = UUID()
        })
        .onAppear { playback.start(); UIApplication.shared.isIdleTimerDisabled = true }
        .accessibilityAction(.escape) { closePlayer() }
        .onDisappear {
            if streaming { rangeServer.stop() }
            playback.stop()
            orientation.setLandscape(false)
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .task(id: "\(controlsVisible)_\(playback.state.rawValue)_\(panel ?? "")_\(interaction)") {
            guard controlsVisible, playback.state == .playing, panel == nil, !pressingControl, !playback.scrubbing, !importing else { return }
            do { try await Task.sleep(nanoseconds: 5_000_000_000) } catch { return }
            if !UIAccessibility.isVoiceOverRunning && !playback.scrubbing && !importing && playback.error.isEmpty && playback.resumeWarning.isEmpty { controlsVisible = false }
        }
        .task(id: feedbackToken) {
            guard !actionFeedback.isEmpty else { return }
            do { try await Task.sleep(nanoseconds: 1_200_000_000) } catch { return }
            actionFeedback = ""
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): Task { await playback.importAttachments(urls, audio: importAudio); controlsVisible = true }
            case .failure(let error): message = error.localizedDescription
            }
        }
        .onChange(of: phase) { state in
            if state != .active && !suspended { suspended = true; playback.suspend(); UIApplication.shared.isIdleTimerDisabled = false }
            if state == .active && suspended { suspended = false; playback.restoreForeground(); UIApplication.shared.isIdleTimerDisabled = true }
        }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { notification in
            if let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
               AVAudioSession.InterruptionType(rawValue: raw) == .began { playback.pause() }
        }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)) { notification in
            if let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
               AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable { playback.pause() }
        }
        .onChange(of: playback.error) { error in if !error.isEmpty { controlsVisible = true; locked = false } }
        .onChange(of: playback.resumeWarning) { warning in if !warning.isEmpty { controlsVisible = true; locked = false } }
    }
    private func updateAspect(_ geometry: GeometryProxy) {
        let insets = geometry.safeAreaInsets
        playback.configureAspect(width: Double(geometry.size.width + (playback.edgeToEdge ? insets.leading + insets.trailing : 0)), height: Double(geometry.size.height + (playback.edgeToEdge ? insets.top + insets.bottom : 0)))
    }
    private var videoGestures: some View {
        HStack(spacing: 0) { tapArea(-10); tapArea(10) }
            .allowsHitTesting(!locked && panel == nil)
    }
    private func tapArea(_ seconds: Double) -> some View {
        Color.clear.contentShape(Rectangle())
            .gesture(TapGesture(count: 2).exclusively(before: TapGesture(count: 1)).onEnded { value in
                switch value {
                case .first:
                    guard playback.seekable else { feedback("Seeking not available yet"); return }
                    playback.seek(seconds); controlsVisible = true
                    feedback(seconds < 0 ? "−10 seconds" : "+10 seconds")
                case .second: controlsVisible.toggle(); interaction = UUID()
                }
            }).accessibilityHidden(true)
    }
    private func feedback(_ text: String) {
        actionFeedback = text; feedbackToken = UUID(); interaction = UUID()
    }
    private func notice(_ text: String) -> some View { Text(text).font(.caption).foregroundStyle(spice).lineLimit(3) }
    private var topBar: some View {
        HStack(spacing: 12) {
            Button(action: closePlayer) { Image(systemName: "chevron.down").font(.system(size: 20, weight: .semibold)).frame(width: 48, height: 48).contentShape(Rectangle()) }
                .accessibilityLabel("Close player").accessibilityIdentifier("player.close").disabled(closing)
            VStack(alignment: .leading, spacing: 4) {
                Text(playback.selection.video.fileName ?? playback.selection.video.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text("CINESPICE").font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(spice)
            }
            Spacer(minLength: 0)
            Button { locked = true } label: { Image(systemName: "lock.open").frame(width: 44, height: 44) }.accessibilityLabel("Lock controls")
        }
    }
    private var transport: some View {
        HStack(spacing: 28) {
            Button { playback.seek(-10); feedback("−10 seconds") } label: { Image(systemName: "gobackward.10").font(.system(size: 28)).frame(width: 48, height: 48) }.disabled(!playback.seekable).accessibilityLabel("Back ten seconds")
            Button { playback.toggle(); feedback(playback.showsPause ? "Resuming…" : "Paused") } label: {
                Image(systemName: playback.showsPause ? "pause.fill" : "play.fill").font(.system(size: 38)).frame(width: 64, height: 60)
            }.accessibilityLabel(playback.showsPause ? "Pause" : "Play")
            Button { playback.seek(10); feedback("+10 seconds") } label: { Image(systemName: "goforward.10").font(.system(size: 28)).frame(width: 48, height: 48) }.disabled(!playback.seekable).accessibilityLabel("Forward ten seconds")
        }
    }
    private var timeline: some View {
        VStack(spacing: 0) {
            Slider(value: $playback.position, in: 0...max(max(playback.duration, playback.position), 1)) { editing in
                playback.scrubbing = editing; interaction = UUID()
                if !editing { playback.commitSeek() }
            }.tint(spice).disabled(!playback.seekable).accessibilityLabel("Playback position")
            HStack { Text(time(playback.position)); Spacer(); Text("−" + time(max(0, playback.duration - playback.position))) }
                .font(.caption.monospacedDigit()).foregroundStyle(.white.opacity(0.7))
        }
    }
    private var tools: some View {
        HStack(spacing: 0) {
            tool(String(format: "%g×", Double(playback.rate)), "speedometer") { panel = "Speed" }
            trackTool
            pictureTool
            rotateTool
            moreTool
        }.padding(.top, 8)
    }
    private func tool(_ title: String, _ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { VStack(spacing: 5) { Image(systemName: symbol).font(.system(size: 19)); Text(title).font(.system(size: 10, weight: .medium)).lineLimit(1) }.frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle()) }
    }
    private var trackTool: some View { tool("Tracks", "captions.bubble") { panel = "Tracks" } }
    private var rotateTool: some View { tool("Rotate", "rotate.right") { orientation.setLandscape(!orientation.landscape) { message = $0 }; feedback(orientation.landscape ? "Landscape locked" : "Portrait locked") } }
    private var pictureTool: some View { tool(playback.aspect, "aspectratio") { panel = "Picture" } }
    private var moreTool: some View { tool("More", "ellipsis") { panel = "More" } }

    private func closePlayer() {
        guard !closing else { return }; closing = true
        // Clear the actual cover binding. Do not rely on an inherited dismiss
        // environment when UIKit/VLC has installed its video drawable.
        if streaming { rangeServer.stop() }
        onClose()
        playback.stop()
    }
    private func options(_ title: String, maxHeight: CGFloat) -> some View {
        VStack(spacing: 4) {
            HStack { Text(title.uppercased()).font(.system(.headline, design: .rounded)); Spacer(); Button { panel = nil } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Close options") }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if title == "Speed" {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 80))], spacing: 10) {
                            ForEach([Float(0.25), 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 3, 4], id: \.self) { rate in
                                option(String(format: "%g×", Double(rate)), selected: rate == playback.rate) { playback.setRate(rate) }
                            }
                        }
                    } else if title == "Picture" {
                        ForEach(["Fit", "Fill", "16:9", "4:3", "21:9"], id: \.self) { ratio in option(ratio, selected: playback.aspect == ratio) { playback.aspect = ratio } }
                        option("Use screen edges", selected: playback.edgeToEdge) { playback.edgeToEdge.toggle() }
                        Text("Fit keeps the original picture inside the camera and home indicator safe areas. Fill crops to the screen. Screen edges may place video beneath the camera cutout.").font(.caption).foregroundStyle(.secondary)
                    } else if title == "Tracks" {
                        trackOptions
                    } else if title == "Subtitle size" {
                        subtitleSizeOptions
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 110))], spacing: 10) {
                            option("Speed") { panel = "Speed" }
                            option("Audio & subtitles") { panel = "Tracks" }
                            option("Subtitle size") { panel = "Subtitle size" }
                            option("Picture") { panel = "Picture" }
                            option("Lock controls") { panel = nil; locked = true }
                        }
                        option("Download video") { let video = playback.selection.video; closePlayer(); telegram.download(video) }
                        Text("Downloads stay in the library. Use Save to Files on the completed video's menu to export its original filename.").font(.caption).foregroundStyle(.secondary)
                        Text("Created by Masalaots · @notmasalaoats").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }.frame(maxHeight: min(440, maxHeight))
        }.padding(12).background(Color(white: 0.08).opacity(0.96), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(spice.opacity(0.3)).allowsHitTesting(false))
    }
    private var trackOptions: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                ForEach(["Audio", "Subtitles"], id: \.self) { name in
                    option(name, selected: trackTab == name) { trackTab = name }
                }
            }
            if trackTab == "Audio" {
                if playback.audioTracks.isEmpty { Text("No audio tracks reported yet.").font(.caption).foregroundStyle(.secondary) }
                ForEach(playback.audioTracks) { track in option(track.name, selected: playback.selectedAudio == track.id) { playback.selectAudio(track.id) } }
                option("Open external audio…") { importAudio = true; importing = true; playback.pause() }
                delay("Audio", value: playback.audioDelay, adjust: playback.adjustAudioDelay)
            } else {
                option("Off", selected: playback.selectedSubtitle == -1) { playback.selectSubtitle(-1) }
                ForEach(playback.subtitleTracks.filter { $0.id >= 0 }) { track in option(track.name, selected: playback.selectedSubtitle == track.id) { playback.selectSubtitle(track.id) } }
                option("Open subtitle file…") { importAudio = false; importing = true; playback.pause() }
                option("Subtitle size · " + playback.subtitleSize.title) { panel = "Subtitle size" }
                delay("Subtitle", value: playback.subtitleDelay, adjust: playback.adjustSubtitleDelay)
            }
        }
    }
    private var subtitleSizeOptions: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("How big should the subtitles be?").font(.subheadline)
            ForEach(SubtitleSize.allCases) { size in
                option(size.title, selected: playback.subtitleSize == size) { playback.setSubtitleSize(size) }
            }
            Text("Saved for future videos. Changes apply to text subtitles; picture subtitles and some styled ASS/SSA tracks keep their own sizing.").font(.caption).foregroundStyle(.secondary)
            if !playback.subtitleStyleWarning.isEmpty { notice(playback.subtitleStyleWarning) }
            option("Back to subtitles") { panel = "Tracks"; trackTab = "Subtitles" }
        }
    }
    private func option(_ title: String, selected: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) { HStack { Text(title); Spacer(); if selected { Image(systemName: "checkmark").foregroundStyle(spice) } }.font(.subheadline).padding(12).frame(minHeight: 44).background(selected ? spice.opacity(0.18) : Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 10)) }
    }
    private func delay(_ title: String, value: Double, adjust: @escaping (Double) -> Void) -> some View {
        HStack { Text(String(format: "%@ delay %+.2f s", title, value)).font(.caption); Spacer(); Button { adjust(-0.25) } label: { Text("−").frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Decrease " + title.lowercased() + " delay"); Button { adjust(0.25) } label: { Text("+").frame(width: 44, height: 44).contentShape(Rectangle()) }.accessibilityLabel("Increase " + title.lowercased() + " delay"); Button { adjust(-value) } label: { Text("Reset").font(.caption).frame(minWidth: 44, minHeight: 44).contentShape(Rectangle()) } }
    }
    private func time(_ value: Double) -> String {
        let seconds = max(0, Int(value.isFinite ? value : 0))
        if seconds >= 3600 { return String(format: "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) }
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

private struct VideoEdges: ViewModifier {
    let expanded: Bool
    @ViewBuilder func body(content: Content) -> some View { if expanded { content.ignoresSafeArea() } else { content } }
}
private struct VLCVideoSurface: UIViewRepresentable {
    let player: VLCMediaPlayer
    func makeUIView(context: Context) -> UIView { let view = UIView(); view.backgroundColor = .black; player.drawable = view; return view }
    // The player belongs to this cover's StateObject; attach once. VLCKit 3.7.4
    // synchronously hops to its playback queue in setDrawable. Reassigning the
    // identical surface on every progress tick can block controls during I/O.
    func updateUIView(_ view: UIView, context: Context) { }
    static func dismantleUIView(_ view: UIView, coordinator: ()) { }
}
