import 'package:flutter/material.dart';
import 'package:transmiti2/app/theme/app_colors.dart';
import 'package:transmiti2/core/storage/image_draft.dart';
import 'package:transmiti2/shared/widgets/app_input.dart';
import 'package:transmiti2/shared/widgets/color_palette.dart';
import 'package:transmiti2/shared/widgets/image_field.dart';
import 'package:transmiti2/shared/widgets/section_label.dart';

/// Datos en edición de un equipo dentro del formulario del partido.
class TeamDraft {
  TeamDraft({required this.name, required this.color, required this.crest});

  final TextEditingController name;
  int color;
  final ImageDraft crest;

  /// Clave del equipo ya registrado cuyo escudo y color se tomaron al
  /// escribir su nombre (nula si no se tomó ninguno).
  String? adoptedKey;
  String? adoptedPath;

  void dispose() => name.dispose();
}

/// Tarjeta con nombre, escudo y color de un equipo.
class TeamEditorCard extends StatelessWidget {
  const TeamEditorCard({
    super.key,
    required this.title,
    required this.keyPrefix,
    required this.draft,
    required this.onChanged,
    required this.onNameChanged,
    this.nameError,
    this.colorError,
    this.locked = false,
  });

  final String title;

  /// Prefijo de las llaves: 'prefijo-name', 'prefijo-crest-pick', etc.
  final String keyPrefix;
  final TeamDraft draft;
  final VoidCallback onChanged;
  final ValueChanged<String> onNameChanged;
  final String? nameError;
  final String? colorError;

  /// Con el partido iniciado no se puede cambiar el equipo.
  final bool locked;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: AppColors.outline),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          SectionLabel(title),
          const SizedBox(height: 16),
          TextField(
            key: Key('$keyPrefix-name'),
            controller: draft.name,
            enabled: !locked,
            textCapitalization: TextCapitalization.words,
            textInputAction: TextInputAction.next,
            onChanged: onNameChanged,
            decoration: appInputDecoration(
              label: 'Nombre del equipo',
              errorText: nameError,
            ),
          ),
          const SizedBox(height: 20),
          ImageField(
            draft: draft.crest,
            pickLabel: 'CARGAR ESCUDO',
            keyPrefix: '$keyPrefix-crest',
            circular: true,
            borderColor: Color(draft.color),
            enabled: !locked,
            onChanged: onChanged,
          ),
          const SizedBox(height: 20),
          Text(
            'Color del equipo',
            style: text.bodyMedium?.copyWith(color: AppColors.textMuted),
          ),
          const SizedBox(height: 10),
          ColorPalette(
            selected: draft.color,
            keyPrefix: keyPrefix,
            enabled: !locked,
            onSelected: (int value) {
              draft.color = value;
              onChanged();
            },
          ),
          if (colorError != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                colorError!,
                style: text.bodySmall?.copyWith(color: AppColors.live),
              ),
            ),
        ],
      ),
    );
  }
}
