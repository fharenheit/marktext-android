// @vitest-environment jsdom

import type { VNode } from 'snabbdom';
import type Format from '../../../block/base/format';
import type { IRenderCursor } from '../../../selection/types';
import type { HTMLTagToken } from '../../types';
import type Renderer from '../index';
import { describe, expect, it } from 'vitest';
import { h } from '../../../utils/snabbdom';
import htmlSvg from '../htmlSvg';

// Inline `<svg>` inside a paragraph used to render as an empty `<svg>` whose
// child shapes went through the inline renderer as plain HTML tokens, so
// nothing was drawn. It now renders a sanitized preview of the whole element.

function makeRenderer(className = 'mu-hide'): Renderer {
    // eslint-disable-next-line no-restricted-syntax
    return {
        getClassName: () => className,
        highlight: (_h: unknown, _block: unknown, start: number, end: number) => [`source:${start}-${end}`],
    } as unknown as Renderer;
}

function makeToken(raw: string): HTMLTagToken {
    // eslint-disable-next-line no-restricted-syntax
    return {
        type: 'html_tag',
        raw,
        tag: 'svg',
        openTag: raw.slice(0, raw.indexOf('>') + 1),
        closeTag: '</svg>',
        attrs: {},
        children: [],
        parent: [],
        range: { start: 0, end: raw.length },
    } as unknown as HTMLTagToken;
}

function render(raw: string, className?: string) {
    return htmlSvg.call(makeRenderer(className), {
        h,
        // eslint-disable-next-line no-restricted-syntax
        cursor: {} as IRenderCursor,
        // eslint-disable-next-line no-restricted-syntax
        block: {} as Format,
        token: makeToken(raw),
        outerClass: undefined,
    }) as VNode[];
}

// A fragment starting with '.' matches a class, anything else the exact tag.
function findBySel(node: VNode | string, fragment: string): VNode | null {
    if (typeof node === 'string')
        return null;
    const sel = node.sel ?? '';
    const matches = fragment.startsWith('.')
        ? sel.split('.').slice(1).includes(fragment.slice(1))
        : sel.split(/[.#]/)[0] === fragment;
    if (matches)
        return node;
    for (const child of node.children ?? []) {
        const found = findBySel(child as VNode, fragment);
        if (found)
            return found;
    }
    return null;
}

describe('htmlSvg', () => {
    it('renders the whole element, children included, in the SVG namespace', () => {
        const [wrapper] = render('<svg width="80" height="40"><circle cx="20" cy="20" r="18"/></svg>');

        expect(wrapper.sel).toBe('span.mu-hide.mu-inline-svg');
        const preview = findBySel(wrapper, '.mu-inline-svg-render')!;
        expect(preview.data?.attrs?.contenteditable).toBe('false');
        const svg = findBySel(preview, 'svg')!;
        expect(svg.data?.ns).toBe('http://www.w3.org/2000/svg');
        const circle = findBySel(svg, 'circle')!;
        expect(circle.data?.ns).toBe('http://www.w3.org/2000/svg');
        expect(circle.data?.attrs?.r).toBe('18');
    });

    it('keeps the source text for editing', () => {
        const raw = '<svg width="8" height="8"></svg>';
        const [wrapper] = render(raw, 'mu-gray');

        expect(wrapper.sel).toBe('span.mu-gray.mu-inline-svg');
        const text = findBySel(wrapper, '.mu-inline-svg-text')!;
        expect(text.children?.[0]).toMatchObject({ text: `source:0-${raw.length}` });
    });

    it('strips scripts and event handlers from the preview', () => {
        const [wrapper] = render(
            '<svg width="8" height="8" onload="alert(1)"><script>alert(2)</script><rect width="8" height="8"/></svg>',
        );

        const svg = findBySel(findBySel(wrapper, '.mu-inline-svg-render')!, 'svg')!;
        expect(svg.data?.attrs?.onload).toBeUndefined();
        expect(findBySel(svg, 'script')).toBeNull();
        expect(findBySel(svg, 'rect')).not.toBeNull();
    });
});
