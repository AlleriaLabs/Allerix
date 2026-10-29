import 'dart:async';
import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import 'config.dart';
import 'jellyfin_client.dart';

void main() => runApp(const AllerixApp());

class AllerixApp extends StatelessWidget {
  const AllerixApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: appDisplayName,
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(afBackgroundValue),
      colorScheme: const ColorScheme.dark(primary: Color(afRedValue), surface: Color(afSurfaceValue)),
      useMaterial3: true,
      inputDecorationTheme: InputDecorationTheme(
        filled: true, fillColor: const Color(afSurfaceValue),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
      ),
    ),
    home: const AppGate(),
  );
}

class AppGate extends StatefulWidget {
  const AppGate({super.key});
  @override
  State<AppGate> createState() => _AppGateState();
}

class _AppGateState extends State<AppGate> {
  JellyfinSession? session;
  bool loading = true;
  @override
  void initState() {
    super.initState();
    JellyfinClient.restoreSession().then((value) {
      if (!mounted) return;
      setState(() { session = value; loading = false; });
    });
  }
  @override
  Widget build(BuildContext context) {
    if (loading) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    if (session == null) return LoginScreen(onSignedIn: (s) => setState(() => session = s));
    return LibraryScreen(session: session!, onSignOut: () async {
      await JellyfinClient.signOut();
      if (mounted) setState(() => session = null);
    });
  }
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.onSignedIn});
  final ValueChanged<JellyfinSession> onSignedIn;
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final server = TextEditingController(text: builtInServerUrl);
  final username = TextEditingController();
  final password = TextEditingController();
  bool busy = false;
  String? error;
  @override
  void dispose() { server.dispose(); username.dispose(); password.dispose(); super.dispose(); }

  Future<void> submit() async {
    if (username.text.trim().isEmpty || password.text.isEmpty || server.text.trim().isEmpty) {
      setState(() => error = 'Enter your server, username, and password.');
      return;
    }
    setState(() { busy = true; error = null; });
    try {
      final s = await JellyfinClient.signIn(serverUrl: server.text, username: username.text, password: password.text);
      if (mounted) widget.onSignedIn(s);
    } catch (e) {
      if (mounted) setState(() => error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(child: Center(child: SingleChildScrollView(
      padding: const EdgeInsets.all(24),
      child: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 440), child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Icon(Icons.movie_filter_outlined, size: 66, color: Color(afRedValue)),
          const SizedBox(height: 16),
          const Text('ALLERIA', textAlign: TextAlign.center, style: TextStyle(letterSpacing: 8, color: Colors.white60)),
          const SizedBox(height: 5),
          const Text('FILMOTHÈQUE', textAlign: TextAlign.center, style: TextStyle(fontSize: 27, letterSpacing: 2, fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          const Text('Your library. Your cinema.', textAlign: TextAlign.center, style: TextStyle(color: Colors.white54)),
          const SizedBox(height: 36),
          TextField(controller: server, keyboardType: TextInputType.url, autocorrect: false, decoration: const InputDecoration(labelText: 'Jellyfin server URL', prefixIcon: Icon(Icons.dns_outlined))),
          const SizedBox(height: 14),
          TextField(controller: username, autocorrect: false, textInputAction: TextInputAction.next, decoration: const InputDecoration(labelText: 'Username', prefixIcon: Icon(Icons.person_outline))),
          const SizedBox(height: 14),
          TextField(controller: password, obscureText: true, onSubmitted: (_) => submit(), decoration: const InputDecoration(labelText: 'Password', prefixIcon: Icon(Icons.lock_outline))),
          if (error != null) ...[
            const SizedBox(height: 14),
            Text(error!, style: const TextStyle(color: Color(0xFFFF7777))),
          ],
          const SizedBox(height: 22),
          FilledButton(
            onPressed: busy ? null : submit,
            style: FilledButton.styleFrom(backgroundColor: const Color(afRedValue), padding: const EdgeInsets.symmetric(vertical: 16)),
            child: busy ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('SIGN IN'),
          ),
          const SizedBox(height: 14),
          const Text('Connects directly to your Jellyfin server. Your session token is stored in iOS secure storage.', textAlign: TextAlign.center, style: TextStyle(fontSize: 12, color: Colors.white38)),
        ],
      )),
    ))),
  );
}

class LibraryScreen extends StatefulWidget {
  const LibraryScreen({super.key, required this.session, required this.onSignOut});
  final JellyfinSession session;
  final Future<void> Function() onSignOut;
  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> {
  late JellyfinClient api;
  List<Map<String, dynamic>> libraries = [];
  List<Map<String, dynamic>> items = [];
  bool busy = true;
  String? error;
  String? selectedLibrary;
  int tab = 0;
  final search = TextEditingController();
  Timer? debounce;
  @override
  void initState() { super.initState(); api = JellyfinClient(widget.session.serverUrl, token: widget.session.token); load(); }
  @override
  void dispose() { search.dispose(); debounce?.cancel(); super.dispose(); }

  Future<void> load({String? parentId, String term = ''}) async {
    setState(() { busy = true; error = null; });
    try {
      final ls = await api.getLibraries(widget.session.userId);
      final found = await api.getItems(widget.session.userId, parentId: parentId, search: term);
      if (!mounted) return;
      setState(() { libraries = ls; items = found; selectedLibrary = parentId; busy = false; });
    } catch (e) {
      if (!mounted) return;
      setState(() { error = e.toString().replaceFirst('Exception: ', ''); busy = false; });
    }
  }

  void searchChanged(String value) {
    debounce?.cancel();
    debounce = Timer(const Duration(milliseconds: 350), () => load(term: value));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('ALLERIA', style: TextStyle(fontSize: 11, letterSpacing: 3, color: Colors.white54)),
          Text('Filmothèque', style: TextStyle(fontWeight: FontWeight.w800)),
        ]),
        actions: [
          IconButton(tooltip: 'Refresh library', onPressed: () => load(parentId: selectedLibrary, term: search.text), icon: const Icon(Icons.refresh)),
          PopupMenuButton<String>(onSelected: (v) { if (v == 'logout') widget.onSignOut(); }, itemBuilder: (_) => const [PopupMenuItem(value: 'logout', child: Text('Sign out'))]),
        ],
      ),
      body: SafeArea(child: Column(children: [
        if (tab == 1) Padding(padding: const EdgeInsets.fromLTRB(16, 4, 16, 12), child: TextField(controller: search, autofocus: true, onChanged: searchChanged, decoration: InputDecoration(hintText: 'Search movies and shows', prefixIcon: const Icon(Icons.search), suffixIcon: IconButton(icon: const Icon(Icons.close), onPressed: () { search.clear(); load(); })))) ,
        if (tab == 0 && libraries.isNotEmpty) SizedBox(height: 58, child: ListView(scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8), children: [
          Padding(padding: const EdgeInsets.symmetric(horizontal: 4), child: ChoiceChip(label: const Text('All'), selected: selectedLibrary == null, onSelected: (_) => load())),
          ...libraries.map((lib) => Padding(padding: const EdgeInsets.symmetric(horizontal: 4), child: ChoiceChip(label: Text((lib['Name'] as String?) ?? 'Library'), selected: selectedLibrary == lib['Id'], onSelected: (_) => load(parentId: lib['Id'] as String?)))),
        ])),
        if (error != null) Expanded(child: _ErrorState(message: error!, retry: () => load(parentId: selectedLibrary, term: search.text))),
        if (error == null && busy) const Expanded(child: Center(child: CircularProgressIndicator())),
        if (error == null && !busy && items.isEmpty) const Expanded(child: Center(child: Text('Nothing to show yet', style: TextStyle(color: Colors.white54)))),
        if (error == null && !busy && items.isNotEmpty) Expanded(child: RefreshIndicator(onRefresh: () => load(parentId: selectedLibrary, term: search.text), child: GridView.builder(
          padding: const EdgeInsets.fromLTRB(12, 4, 12, 20),
          gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(maxCrossAxisExtent: 190, mainAxisSpacing: 16, crossAxisSpacing: 12, childAspectRatio: 0.57),
          itemCount: items.length,
          itemBuilder: (context, i) => _PosterCard(item: items[i], api: api, onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => DetailScreen(item: items[i], session: widget.session, api: api)))),
        ))),
      ])),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (value) {
          setState(() => tab = value);
          if (value == 0) { search.clear(); load(); }
          if (value == 1) { search.clear(); load(term: ''); }
          if (value == 2) _showSettings();
        },
        destinations: const [
          NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: 'Home'),
          NavigationDestination(icon: Icon(Icons.search), label: 'Search'),
          NavigationDestination(icon: Icon(Icons.tune), label: 'Settings'),
        ],
      ),
    );
  }

  void _showSettings() {
    showModalBottomSheet<void>(context: context, showDragHandle: true, builder: (context) => SafeArea(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
      const Text('Settings', style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold)),
      const SizedBox(height: 16),
      ListTile(leading: const Icon(Icons.dns_outlined), title: const Text('Server'), subtitle: Text(widget.session.serverUrl)),
      ListTile(leading: const Icon(Icons.person_outline), title: const Text('Signed in as'), subtitle: Text(widget.session.username)),
      ListTile(leading: const Icon(Icons.logout), title: const Text('Sign out'), onTap: () { Navigator.pop(context); widget.onSignOut(); }),
      const SizedBox(height: 8),
    ]))));
  }
}

class _PosterCard extends StatelessWidget {
  const _PosterCard({required this.item, required this.api, required this.onTap});
  final Map<String, dynamic> item;
  final JellyfinClient api;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    final id = item['Id'] as String? ?? '';
    final name = item['Name'] as String? ?? 'Untitled';
    final played = item['UserData'] is Map && (item['UserData']['Played'] == true);
    return InkWell(onTap: onTap, borderRadius: BorderRadius.circular(12), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Expanded(child: Stack(fit: StackFit.expand, children: [
        ClipRRect(borderRadius: BorderRadius.circular(12), child: Image.network(api.imageUrl(id), fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(color: const Color(afSurfaceValue), child: const Icon(Icons.movie_outlined, size: 44, color: Colors.white24)))),
        if (played) const Positioned(right: 8, top: 8, child: Icon(Icons.check_circle, color: Color(afRedValue))),
        if (item['Type'] == 'Series') const Positioned(left: 8, top: 8, child: _TypePill(label: 'SERIES')),
      ])),
      const SizedBox(height: 8),
      Text(name, maxLines: 2, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
      const SizedBox(height: 3),
      Text((item['ProductionYear']?.toString() ?? (item['Type'] as String? ?? 'Media')), style: const TextStyle(fontSize: 12, color: Colors.white54)),
    ]));
  }
}

class _TypePill extends StatelessWidget {
  const _TypePill({required this.label});
  final String label;
  @override
  Widget build(BuildContext context) => Container(padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4), decoration: BoxDecoration(color: Colors.black87, borderRadius: BorderRadius.circular(5)), child: Text(label, style: const TextStyle(fontSize: 9, fontWeight: FontWeight.bold, letterSpacing: 1)));
}

class DetailScreen extends StatefulWidget {
  const DetailScreen({super.key, required this.item, required this.session, required this.api});
  final Map<String, dynamic> item;
  final JellyfinSession session;
  final JellyfinClient api;
  @override
  State<DetailScreen> createState() => _DetailScreenState();
}

class _DetailScreenState extends State<DetailScreen> {
  late Map<String, dynamic> item;
  List<Map<String, dynamic>> seasons = [];
  List<Map<String, dynamic>> episodes = [];
  bool busy = true;
  String? error;
  @override
  void initState() { super.initState(); item = widget.item; loadDetails(); }
  Future<void> loadDetails() async {
    try {
      final id = item['Id'] as String;
      final fresh = await widget.api.getItem(id, widget.session.userId);
      List<Map<String, dynamic>> s = [];
      if (fresh['Type'] == 'Series') s = await widget.api.getSeasons(id, widget.session.userId);
      if (!mounted) return;
      setState(() { item = fresh; seasons = s; busy = false; });
    } catch (e) { if (mounted) setState(() { error = e.toString(); busy = false; }); }
  }
  Future<void> chooseSeason(Map<String, dynamic> season) async {
    try {
      final e = await widget.api.getEpisodes(item['Id'] as String, season['Id'] as String, widget.session.userId);
      if (!mounted) return;
      setState(() => episodes = e);
    } catch (e) { if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString()))); }
  }
  void play(Map<String, dynamic> media) {
    Navigator.of(context).push(MaterialPageRoute(builder: (_) => PlayerScreen(item: media, session: widget.session, api: widget.api)));
  }
  @override
  Widget build(BuildContext context) {
    final id = item['Id'] as String? ?? '';
    final overview = item['Overview'] as String? ?? 'No description is available for this title.';
    final isSeries = item['Type'] == 'Series';
    return Scaffold(
      body: CustomScrollView(slivers: [
        SliverAppBar(expandedHeight: 300, pinned: true, backgroundColor: const Color(afBackgroundValue), flexibleSpace: FlexibleSpaceBar(background: Stack(fit: StackFit.expand, children: [
          Image.network(widget.api.imageUrl(id, type: 'Backdrop', width: 1400), fit: BoxFit.cover, errorBuilder: (_, __, ___) => Image.network(widget.api.imageUrl(id, width: 1000), fit: BoxFit.cover, errorBuilder: (_, __, ___) => Container(color: const Color(afSurfaceValue)))),
          const DecoratedBox(decoration: BoxDecoration(gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Colors.transparent, Color(0xEE08090D)], stops: [0.2, 1]))),
          Positioned(left: 20, right: 20, bottom: 22, child: Text((item['Name'] as String?) ?? 'Title', style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w800))),
        ]))),
        SliverToBoxAdapter(child: Padding(padding: const EdgeInsets.all(20), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Wrap(spacing: 10, runSpacing: 8, children: [
            if (item['ProductionYear'] != null) _TypePill(label: item['ProductionYear'].toString()),
            if (item['CommunityRating'] != null) _TypePill(label: '★ ' + (item['CommunityRating'] as num).toStringAsFixed(1)),
            if (item['OfficialRating'] != null) _TypePill(label: item['OfficialRating'] as String),
            if (item['RunTimeTicks'] is int) _TypePill(label: _duration(item['RunTimeTicks'] as int)),
          ]),
          const SizedBox(height: 18),
          if (!isSeries) SizedBox(width: double.infinity, child: FilledButton.icon(onPressed: () => play(item), icon: const Icon(Icons.play_arrow), label: const Text('PLAY'), style: FilledButton.styleFrom(backgroundColor: const Color(afRedValue), padding: const EdgeInsets.all(15)))),
          const SizedBox(height: 18),
          Text(overview, style: const TextStyle(height: 1.55, color: Colors.white70)),
          if (item['Genres'] is List && (item['Genres'] as List).isNotEmpty) Padding(padding: const EdgeInsets.only(top: 16), child: Text((item['Genres'] as List).join('  •  '), style: const TextStyle(color: Colors.white54))),
          if (busy) const Padding(padding: EdgeInsets.all(20), child: Center(child: CircularProgressIndicator())),
          if (error != null) Text(error!, style: const TextStyle(color: Colors.redAccent)),
          if (isSeries && seasons.isNotEmpty) ...[
            const SizedBox(height: 28),
            const Text('Episodes', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
            const SizedBox(height: 12),
            DropdownButtonFormField<Map<String, dynamic>>(decoration: const InputDecoration(labelText: 'Season'), items: seasons.map((s) => DropdownMenuItem(value: s, child: Text((s['Name'] as String?) ?? 'Season'))).toList(), onChanged: (s) { if (s != null) chooseSeason(s); }),
            ...episodes.map((ep) => ListTile(contentPadding: EdgeInsets.zero, leading: ClipRRect(borderRadius: BorderRadius.circular(6), child: SizedBox(width: 100, height: 60, child: Image.network(widget.api.imageUrl(ep['Id'] as String, type: 'Primary', width: 300), fit: BoxFit.cover, errorBuilder: (_, __, ___) => const ColoredBox(color: Color(afSurfaceValue), child: Icon(Icons.play_circle_outline))))), title: Text((ep['Name'] as String?) ?? 'Episode'), subtitle: Text(ep['Overview'] as String? ?? '', maxLines: 2, overflow: TextOverflow.ellipsis), trailing: const Icon(Icons.play_circle_fill, color: Color(afRedValue)), onTap: () => play(ep))),
          ],
        ]))),
      ]),
    );
  }
}

String _duration(int ticks) {
  final minutes = ticks ~/ 600000000;
  if (minutes >= 60) return (minutes ~/ 60).toString() + 'h ' + (minutes % 60).toString() + 'm';
  return minutes.toString() + ' min';
}

class PlayerScreen extends StatefulWidget {
  const PlayerScreen({super.key, required this.item, required this.session, required this.api});
  final Map<String, dynamic> item;
  final JellyfinSession session;
  final JellyfinClient api;
  @override
  State<PlayerScreen> createState() => _PlayerScreenState();
}

class _PlayerScreenState extends State<PlayerScreen> {
  VideoPlayerController? controller;
  bool busy = true;
  bool controls = true;
  bool sentPlaying = false;
  String? error;
  int bitrate = 8000000;
  int? audioIndex;
  int? subtitleIndex;
  int lastProgressSeconds = -1;
  final String playSessionId = DateTime.now().microsecondsSinceEpoch.toString();
  Timer? hideControls;
  List<Map<String, dynamic>> get streams {
    final sources = widget.item['MediaSources'];
    if (sources is! List || sources.isEmpty || sources.first is! Map) return [];
    final mediaStreams = sources.first['MediaStreams'];
    if (mediaStreams is! List) return [];
    return mediaStreams.whereType<Map>().map((e) => Map<String, dynamic>.from(e)).toList();
  }
  @override
  void initState() { super.initState(); initialise(); }
  Future<void> initialise() async {
    setState(() { busy = true; error = null; });
    try {
      final old = controller;
      controller = null;
      if (old != null) { old.removeListener(onTick); await old.dispose(); }
      final c = VideoPlayerController.networkUrl(Uri.parse(widget.api.streamUrl(widget.item['Id'] as String, maxBitrate: bitrate, audioStreamIndex: audioIndex, subtitleStreamIndex: subtitleIndex)), httpHeaders: {'X-Emby-Token': widget.session.token});
      controller = c;
      await c.initialize();
      c.addListener(onTick);
      await c.play();
      if (!sentPlaying) {
        sentPlaying = true;
        await widget.api.reportPlayback('Playing', payload(c, false));
      }
      if (mounted) setState(() => busy = false);
      scheduleHide();
    } catch (e) {
      if (mounted) setState(() { error = 'Could not play this stream. Try another quality or check server transcoding settings. ' + e.toString(); busy = false; });
    }
  }
  Map<String, dynamic> payload(VideoPlayerController c, bool paused) {
    final pos = c.value.position.inMilliseconds * 10000;
    return {'ItemId': widget.item['Id'], 'PlaySessionId': playSessionId, 'PositionTicks': pos, 'IsPaused': paused, 'IsMuted': c.value.volume == 0, 'PlayMethod': 'Transcode', 'CanSeek': true};
  }
  void onTick() {
    final c = controller;
    if (c == null || !c.value.isInitialized || !mounted) return;
    final seconds = c.value.position.inSeconds;
    if (seconds ~/ 15 != lastProgressSeconds ~/ 15) {
      lastProgressSeconds = seconds;
      widget.api.reportPlayback('Playing/Progress', payload(c, !c.value.isPlaying));
    }
    if (c.value.hasError && error == null) setState(() => error = c.value.errorDescription);
    if (c.value.isPlaying && controls) scheduleHide();
  }
  void scheduleHide() {
    hideControls?.cancel();
    hideControls = Timer(const Duration(seconds: 4), () { if (mounted && controller?.value.isPlaying == true) setState(() => controls = false); });
  }
  @override
  void dispose() {
    hideControls?.cancel();
    final c = controller;
    if (c != null) {
      c.removeListener(onTick);
      widget.api.reportPlayback('Playing/Stopped', payload(c, true));
      c.dispose();
    }
    super.dispose();
  }
  String clock(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return h > 0 ? h.toString() + ':' + m + ':' + s : m + ':' + s;
  }
  Future<void> openSettings() async {
    final qualities = <String, int>{'Auto / 12 Mbps': 12000000, '1080p / 8 Mbps': 8000000, '720p / 4 Mbps': 4000000, '480p / 2 Mbps': 2000000};
    final audio = streams.where((s) => s['Type'] == 'Audio').toList();
    final subs = streams.where((s) => s['Type'] == 'Subtitle').toList();
    await showModalBottomSheet<void>(context: context, showDragHandle: true, builder: (context) => StatefulBuilder(builder: (context, sheetSet) => SafeArea(child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(20), children: [
      const Text('Playback settings', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
      const SizedBox(height: 12),
      const Text('Streaming quality', style: TextStyle(color: Colors.white60)),
      ...qualities.entries.map((q) => RadioListTile<int>(value: q.value, groupValue: bitrate, title: Text(q.key), onChanged: (v) { if (v != null) { setState(() => bitrate = v); sheetSet(() {}); Navigator.pop(context); initialise(); } })),
      const Divider(),
      const Text('Audio track', style: TextStyle(color: Colors.white60)),
      ...audio.map((s) { final idx = s['Index'] as int?; final title = ((s['Language'] as String?) ?? 'Unknown') + ' · ' + ((s['Codec'] as String?) ?? 'Audio'); return RadioListTile<int>(value: idx ?? -1, groupValue: audioIndex ?? -1, title: Text(title), onChanged: (v) { setState(() => audioIndex = v == -1 ? null : v); Navigator.pop(context); initialise(); }); }),
      const Divider(),
      const Text('Subtitles', style: TextStyle(color: Colors.white60)),
      RadioListTile<int?>(value: null, groupValue: subtitleIndex, title: const Text('Off'), onChanged: (_) { setState(() => subtitleIndex = null); Navigator.pop(context); initialise(); }),
      ...subs.map((s) { final idx = s['Index'] as int?; return RadioListTile<int?>(value: idx, groupValue: subtitleIndex, title: Text((s['Language'] as String?) ?? (s['DisplayTitle'] as String?) ?? 'Subtitle'), onChanged: (v) { setState(() => subtitleIndex = v); Navigator.pop(context); initialise(); }); }),
      const SizedBox(height: 12),
      const Text('Track and subtitle availability depends on the server stream. Changing options restarts playback.', style: TextStyle(color: Colors.white54, fontSize: 12)),
    ]))));
  }
  @override
  Widget build(BuildContext context) {
    final c = controller;
    final value = c?.value;
    return Scaffold(backgroundColor: Colors.black, body: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: () { setState(() => controls = !controls); if (controls) scheduleHide(); },
      onDoubleTap: () { final c = controller; if (c != null) c.seekTo(c.value.position + const Duration(seconds: 10)); },
      child: Stack(fit: StackFit.expand, children: [
        if (value?.isInitialized == true) Center(child: AspectRatio(aspectRatio: value!.aspectRatio, child: VideoPlayer(c!))),
        if (busy) const Center(child: CircularProgressIndicator(color: Color(afRedValue))),
        if (error != null) Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [const Icon(Icons.error_outline, size: 48, color: Colors.redAccent), const SizedBox(height: 12), Text(error!, textAlign: TextAlign.center), const SizedBox(height: 16), FilledButton(onPressed: initialise, child: const Text('Retry'))]))),
        if (controls && error == null) Container(decoration: const BoxDecoration(gradient: LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [Color(0xCC000000), Colors.transparent, Color(0xCC000000)], stops: [0, 0.45, 1])), child: SafeArea(child: Column(children: [
          Row(children: [IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.arrow_back)), Expanded(child: Text((widget.item['Name'] as String?) ?? 'Now playing', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600))), IconButton(tooltip: 'Audio, subtitles and quality', onPressed: openSettings, icon: const Icon(Icons.tune))]),
          const Spacer(),
          Row(mainAxisAlignment: MainAxisAlignment.center, children: [
            IconButton(iconSize: 38, onPressed: () { final p = c?.value.position ?? Duration.zero; c?.seekTo(p - const Duration(seconds: 10)); }, icon: const Icon(Icons.replay_10)),
            const SizedBox(width: 26),
            IconButton(iconSize: 64, onPressed: () { if (c == null) return; setState(() { c.value.isPlaying ? c.pause() : c.play(); }); widget.api.reportPlayback('Playing/Progress', payload(c, c.value.isPlaying)); scheduleHide(); }, icon: Icon(value?.isPlaying == true ? Icons.pause_circle_filled : Icons.play_circle_fill, color: const Color(afRedValue))),
            const SizedBox(width: 26),
            IconButton(iconSize: 38, onPressed: () { final p = c?.value.position ?? Duration.zero; c?.seekTo(p + const Duration(seconds: 30)); }, icon: const Icon(Icons.forward_30)),
          ]),
          const Spacer(),
          if (value?.isInitialized == true) Padding(padding: const EdgeInsets.fromLTRB(18, 0, 18, 12), child: Column(children: [
            VideoProgressIndicator(c!, allowScrubbing: true, colors: const VideoProgressColors(playedColor: Color(afRedValue), bufferedColor: Colors.white38, backgroundColor: Colors.white24)),
            const SizedBox(height: 6),
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [Text(clock(value!.position), style: const TextStyle(fontSize: 12)), Text('-' + clock(value.duration - value.position), style: const TextStyle(fontSize: 12))]),
          ])),
        ]))),
      ]),
    ));
  }
}

class _ErrorState extends StatelessWidget {
  const _ErrorState({required this.message, required this.retry});
  final String message;
  final VoidCallback retry;
  @override
  Widget build(BuildContext context) => Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
    const Icon(Icons.cloud_off_outlined, size: 48, color: Colors.white38),
    const SizedBox(height: 12),
    Text(message, textAlign: TextAlign.center),
    const SizedBox(height: 16),
    FilledButton.icon(onPressed: retry, icon: const Icon(Icons.refresh), label: const Text('Try again')),
  ])));
}
