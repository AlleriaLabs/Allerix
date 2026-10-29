import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class JellyfinSession {
  const JellyfinSession({required this.serverUrl, required this.token, required this.userId, required this.username});
  final String serverUrl, token, userId, username;
  Map<String, dynamic> toJson() => {'serverUrl': serverUrl, 'token': token, 'userId': userId, 'username': username};
  factory JellyfinSession.fromJson(Map<String, dynamic> j) => JellyfinSession(serverUrl: j['serverUrl'] as String, token: j['token'] as String, userId: j['userId'] as String, username: j['username'] as String);
}

class JellyfinClient {
  JellyfinClient(String serverUrl, {String? token}) : baseUrl = _normalise(serverUrl), _token = token {
    _dio = Dio(BaseOptions(baseUrl: baseUrl, connectTimeout: const Duration(seconds: 15), receiveTimeout: const Duration(seconds: 45), headers: {
      'Accept': 'application/json',
      'Content-Type': 'application/json',
      'X-Emby-Authorization': 'MediaBrowser Client="Allerix", Device="iOS", DeviceId="allerix-ios", Version="0.2.0"',
      if (token != null) 'X-Emby-Token': token,
    }));
  }
  late final Dio _dio;
  final String baseUrl;
  final String? _token;
  static const _storage = FlutterSecureStorage();
  String get token => _token ?? '';
  static String _normalise(String value) {
    var v = value.trim();
    while (v.endsWith('/')) { v = v.substring(0, v.length - 1); }
    if (!v.startsWith('https://') && !v.startsWith('http://')) v = 'https://' + v;
    return v;
  }
  static Future<JellyfinSession> signIn({required String serverUrl, required String username, required String password}) async {
    final c = JellyfinClient(serverUrl);
    try {
      final r = await c._dio.post<Map<String, dynamic>>('/Users/AuthenticateByName', data: {'Username': username.trim(), 'Pw': password});
      final d = r.data;
      if (d == null || d['AccessToken'] == null || d['User'] == null) throw const FormatException('Incomplete Jellyfin sign-in response.');
      final u = Map<String, dynamic>.from(d['User'] as Map);
      final s = JellyfinSession(serverUrl: c.baseUrl, token: d['AccessToken'] as String, userId: u['Id'] as String, username: (u['Name'] as String?) ?? username.trim());
      await _storage.write(key: 'allerix.session', value: jsonEncode(s.toJson()));
      return s;
    } on DioException catch (e) { throw Exception(_error(e)); }
  }
  static Future<JellyfinSession?> restoreSession() async {
    final raw = await _storage.read(key: 'allerix.session');
    if (raw == null) return null;
    try {
      final s = JellyfinSession.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
      await JellyfinClient(s.serverUrl, token: s.token)._dio.get('/Users/' + s.userId);
      return s;
    } catch (_) { await _storage.delete(key: 'allerix.session'); return null; }
  }
  static Future<void> signOut() => _storage.delete(key: 'allerix.session');
  static String _error(DioException e) {
    final code = e.response?.statusCode;
    if (code == 401 || code == 403) return 'Incorrect username or password, or access was denied.';
    if (code != null) return 'Jellyfin returned HTTP ' + code.toString() + '.';
    if (e.type == DioExceptionType.connectionTimeout || e.type == DioExceptionType.receiveTimeout) return 'The server took too long to respond. Check the URL and connection.';
    return e.message ?? 'Could not connect to Jellyfin.';
  }
  Future<dynamic> _get(String path, {Map<String, dynamic>? query}) async {
    try { return (await _dio.get<dynamic>(path, queryParameters: query)).data; }
    on DioException catch (e) { throw Exception(_error(e)); }
  }
  List<Map<String, dynamic>> _items(dynamic d) {
    if (d is! Map || d['Items'] is! List) return [];
    return (d['Items'] as List).whereType<Map>().map((x) => Map<String, dynamic>.from(x)).toList();
  }
  Future<List<Map<String, dynamic>>> getLibraries(String userId) async => _items(await _get('/Users/' + userId + '/Views'));
  Future<List<Map<String, dynamic>>> getItems(String userId, {String? parentId, String search = '', int limit = 60}) async {
    final q = <String, dynamic>{'UserId': userId, 'Limit': limit, 'StartIndex': 0, 'Recursive': true, 'Fields': 'Overview,Genres,People,MediaSources,RunTimeTicks,UserData,DateCreated', 'EnableImageTypes': 'Primary,Backdrop,Thumb,Logo', 'SortBy': 'SortName', 'SortOrder': 'Ascending', 'IncludeItemTypes': 'Movie,Series,Episode,Video'};
    if (parentId != null && parentId.isNotEmpty) q['ParentId'] = parentId;
    if (search.trim().isNotEmpty) q['SearchTerm'] = search.trim();
    return _items(await _get('/Users/' + userId + '/Items', query: q));
  }
  Future<List<Map<String, dynamic>>> search(String userId, String term) => getItems(userId, search: term, limit: 50);
  Future<Map<String, dynamic>> getItem(String itemId, String userId) async => Map<String, dynamic>.from(await _get('/Users/' + userId + '/Items/' + itemId, query: {'Fields': 'Overview,Genres,People,MediaSources,RunTimeTicks,UserData,DateCreated', 'EnableImageTypes': 'Primary,Backdrop,Thumb,Logo'}) as Map);
  Future<List<Map<String, dynamic>>> getSeasons(String seriesId, String userId) async => _items(await _get('/Shows/' + seriesId + '/Seasons', query: {'UserId': userId}));
  Future<List<Map<String, dynamic>>> getEpisodes(String seriesId, String seasonId, String userId) async => _items(await _get('/Shows/' + seriesId + '/Episodes', query: {'UserId': userId, 'SeasonId': seasonId, 'Fields': 'Overview,MediaSources,RunTimeTicks,UserData'}));
  String imageUrl(String id, {String type = 'Primary', int width = 900}) => baseUrl + '/Items/' + id + '/Images/' + type + '?maxWidth=' + width.toString() + '&quality=90&api_key=' + Uri.encodeQueryComponent(token);
  String streamUrl(String id, {int? maxBitrate, int? audioStreamIndex, int? subtitleStreamIndex}) {
    final p = <String, String>{'api_key': token, 'VideoCodec': 'h264', 'AudioCodec': 'aac', if (maxBitrate != null) 'MaxStreamingBitrate': maxBitrate.toString(), if (audioStreamIndex != null) 'AudioStreamIndex': audioStreamIndex.toString(), if (subtitleStreamIndex != null) 'SubtitleStreamIndex': subtitleStreamIndex.toString()};
    return baseUrl + '/Videos/' + id + '/master.m3u8?' + p.entries.map((e) => Uri.encodeQueryComponent(e.key) + '=' + Uri.encodeQueryComponent(e.value)).join('&');
  }
  Future<void> reportPlayback(String endpoint, Map<String, dynamic> payload) async {
    try { await _dio.post('/Sessions/' + endpoint, data: payload); } catch (_) {}
  }
}
