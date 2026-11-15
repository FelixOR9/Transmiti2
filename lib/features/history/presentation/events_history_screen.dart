import 'package:flutter/material.dart';
import 'package:transmiti2/app/app_scope.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/features/match/application/match_session.dart';
import 'package:transmiti2/features/match/domain/match_event.dart';
import 'package:transmiti2/features/match/presentation/event_labels.dart';

/// Historial cronológico del partido, leído de SQLite: goles, inicio y fin de
/// los tiempos, descanso, final y correcciones.
class EventsHistoryScreen extends StatelessWidget {
  const EventsHistoryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final MatchSession session = AppScope.of(context).session;
    return ListenableBuilder(
      listenable: session,
      builder: (BuildContext context, Widget? child) {
        final List<MatchEvent> events = session.events;
        return Scaffold(
          appBar: AppBar(title: const Text('Historial')),
          body: SafeArea(
            child: !session.hasMatch
                ? const _Message('Todavía no hay un partido preparado.')
                : events.isEmpty
                ? const _Message(
                    'Todavía no hay eventos. Aparecerán aquí cuando empiece el partido.',
                  )
                : Center(
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 760),
                      child: ListView.separated(
                        padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
                        itemCount: events.length,
                        separatorBuilder: (BuildContext context, int index) =>
                            const SizedBox(height: 10),
                        itemBuilder: (BuildContext context, int index) {
                          final MatchEvent event = events[index];
                          return _EventTile(
                            event: event,
                            line: describeEvent(event, session),
                          );
                        },
                      ),
                    ),
                  ),
          ),
        );
      },
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Text(
          text,
          key: const Key('history-empty'),
          textAlign: TextAlign.center,
          style: Theme.of(
            context,
          ).textTheme.titleMedium?.copyWith(color: AppColors.textMuted),
        ),
      ),
    );
  }
}

class _EventTile extends StatelessWidget {
  const _EventTile({required this.event, required this.line});

  final MatchEvent event;
  final EventLine line;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final TextDecoration? decoration = line.struck
        ? TextDecoration.lineThrough
        : null;
    return Container(
      key: Key('event-${event.id}'),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.outline),
      ),
      child: Row(
        children: <Widget>[
          SizedBox(
            width: 58,
            child: Column(
              children: <Widget>[
                Text(
                  eventTimeLabel(event),
                  style: text.titleMedium?.copyWith(
                    color: AppColors.textPrimary,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                Text(
                  eventPeriodTag(event),
                  style: text.labelSmall?.copyWith(color: AppColors.textMuted),
                ),
              ],
            ),
          ),
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: AppColors.surfaceHigh,
              border: Border.all(color: line.color, width: 2),
            ),
            child: Icon(line.icon, size: 20, color: line.color),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  line.title,
                  style: text.titleSmall?.copyWith(
                    color: AppColors.textPrimary,
                    fontWeight: FontWeight.w700,
                    decoration: decoration,
                  ),
                ),
                if (line.subtitle != null)
                  Text(
                    line.subtitle!,
                    style: text.bodySmall?.copyWith(
                      color: AppColors.textMuted,
                      decoration: decoration,
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
