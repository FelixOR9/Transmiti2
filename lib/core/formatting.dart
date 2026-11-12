/// Rellena con ceros a la izquierda hasta tener al menos dos dígitos.
String twoDigits(int n) => n.toString().padLeft(2, '0');

/// Fecha local con el formato dd/MM/aaaa.
String formatDate(int epochMs) {
  final DateTime d = DateTime.fromMillisecondsSinceEpoch(epochMs);
  return '${twoDigits(d.day)}/${twoDigits(d.month)}/${d.year}';
}

/// Milisegundos como MM:SS (los minutos pueden pasar de 99).
String formatClock(int ms) {
  final int totalSeconds = (ms < 0 ? 0 : ms) ~/ 1000;
  return '${twoDigits(totalSeconds ~/ 60)}:${twoDigits(totalSeconds % 60)}';
}
