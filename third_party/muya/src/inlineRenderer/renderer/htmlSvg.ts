import type { HTMLTagToken, ISyntaxRenderOptions } from '../types';
import type Renderer from './index';
import { CLASS_NAMES, PREVIEW_DOMPURIFY_CONFIG } from '../../config';
import { sanitize } from '../../utils';
import { htmlToVNode } from '../../utils/snabbdom';

// Inline `<svg>…</svg>` inside a paragraph. The generic raw-HTML path renders
// the `<svg>` element but sends its children (`<circle/>`, `<path/>`, …)
// through the inline renderer as plain HTML tokens, so nothing is drawn.
// Like `<ruby>`, the whole element is instead rendered as a sanitized preview
// next to its (hidden unless edited) source text.
export default function htmlSvg(
    this: Renderer,
    {
        h,
        cursor,
        block,
        token,
        outerClass,
    }: ISyntaxRenderOptions & { token: HTMLTagToken },
) {
    const className = this.getClassName(outerClass, block, token, cursor);
    const { start, end } = token.range;
    const content = this.highlight(h, block, start, end, token);
    const html = sanitize(token.raw, PREVIEW_DOMPURIFY_CONFIG, false) as string;
    const previewNodes = (/<svg[\s>]/i.test(html) ? htmlToVNode(html) : undefined) ?? [];

    return [
        h(`span.${className}.${CLASS_NAMES.MU_INLINE_SVG}`, [
            h(
                `span.${CLASS_NAMES.MU_INLINE_RULE}.${CLASS_NAMES.MU_INLINE_SVG_TEXT}`,
                { attrs: { spellcheck: 'false' } },
                content,
            ),
            ...(previewNodes.length
                ? [
                        h(
                            `span.${CLASS_NAMES.MU_INLINE_SVG_RENDER}`,
                            {
                                attrs: {
                                    contenteditable: 'false',
                                    spellcheck: 'false',
                                },
                                dataset: {
                                    start: String(start),
                                    end: String(end),
                                },
                            },
                            previewNodes,
                        ),
                    ]
                : []),
        ]),
    ];
}
