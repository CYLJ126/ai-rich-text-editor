import {ReactRenderer} from '@tiptap/react';
import type {
  SuggestionKeyDownProps,
  SuggestionOptions,
  SuggestionProps,
} from '@tiptap/suggestion';
import {i18nText} from '@/utils/i18n';
import MentionList, {
  type MentionListHandle,
  type MentionListProps,
} from './MentionList';

type MentionSelection = { id: string };
type MentionSuggestionOptions = Partial<
  Omit<SuggestionOptions<string, MentionSelection>, 'editor'>
>;

const suggestion: MentionSuggestionOptions = {
  items: ({query}) =>
    [
      i18nText('app.article.mymention.suggestion.f359f44e'),
      i18nText('app.article.mymention.suggestion.92a0dd0f'),
      i18nText('app.article.mymention.suggestion.660f3264'),
      i18nText('app.article.mymention.suggestion.711fe082'),
      i18nText('app.article.mymention.suggestion.05a3660a'),
      i18nText('app.article.mymention.suggestion.7acd9bb8'),
      i18nText('app.article.mymention.suggestion.3657b147'),
    ].filter((item) => item.toLowerCase().startsWith(query.toLowerCase())),

  render: () => {
    let component:
      | ReactRenderer<MentionListHandle, MentionListProps>
      | undefined;
    let unmount: (() => void) | undefined;

    const listProps = (
      props: SuggestionProps<string, MentionSelection>,
    ): MentionListProps => ({
      items: props.items,
      command: props.command,
    });

    return {
      onStart: (props) => {
        component = new ReactRenderer<MentionListHandle, MentionListProps>(
          MentionList,
          {
            props: listProps(props),
            editor: props.editor,
          },
        );
        unmount = props.mount(component.element);
      },
      onUpdate: (props) => component?.updateProps(listProps(props)),
      onKeyDown: (props: SuggestionKeyDownProps) => {
        if (props.event.key === 'Escape') {
          unmount?.();
          component?.destroy();
          return true;
        }
        return component?.ref?.onKeyDown({event: props.event}) ?? false;
      },
      onExit: () => {
        unmount?.();
        component?.destroy();
        component = undefined;
        unmount = undefined;
      },
    };
  },
};

export default suggestion;
