import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

/// Short stable id for this install; people share it so others can address messages to them.
class NodeIdentity {
  static const String _key = 'gazalink_node_id';

  static Future<String> load() async {
    final prefs = await SharedPreferences.getInstance();
    var id = prefs.getString(_key);
    if (id == null) {
      id = const Uuid().v4().substring(0, 8).toUpperCase();
      await prefs.setString(_key, id);
    }
    return id;
  }
}
