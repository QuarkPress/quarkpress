import { LitElement, html, css } from 'lit';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import { modules } from 'build-time-data';
import '@vaadin/grid';

/** A grid cell showing text that a narrow column may cut off, with the whole text as its tooltip. */
function full(text) {
    const value = text == null ? '' : String(text);
    return html`<span title="${value}">${value}</span>`;
}

/**
 * The QuarkPress modules installed in the application: version, capability, per-site features with their defaults,
 * migration band and configuration prefixes.
 */
export class QwcQuarkpressModules extends LitElement {

    static styles = css`
        :host { display: flex; flex-direction: column; height: 100%; }
        vaadin-grid { height: 100%; }
        .none { color: var(--lumo-contrast-50pct); }
    `;

    static properties = { _modules: { state: true } };

    constructor() {
        super();
        this._modules = modules == null ? [] : modules;
    }

    render() {
        return html`
            <vaadin-grid .items="${this._modules}" theme="no-border row-stripes">
                <vaadin-grid-column header="Artifact" auto-width resizable
                    ${columnBodyRenderer(m => full(m.artifactId), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Version" auto-width
                    ${columnBodyRenderer(m => html`${m.version}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Capability" auto-width resizable
                    ${columnBodyRenderer(m => this._orNone(m.capability), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Features (default)" auto-width resizable
                    ${columnBodyRenderer(m => this._features(m.features), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Migration band" auto-width
                    ${columnBodyRenderer(m => this._orNone(m.migrationBand), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Configuration prefixes" auto-width resizable
                    ${columnBodyRenderer(m => this._list(m.configPrefixes), [])}></vaadin-grid-column>
            </vaadin-grid>`;
    }

    _features(features) {
        if (features == null || features.length === 0) {
            return html`<span class="none">none</span>`;
        }
        return full(features.map(f => `${f.key}=${f.defaultEnabled ? 'on' : 'off'}`).join(', '));
    }

    _list(values) {
        if (values == null || values.length === 0) {
            return html`<span class="none">none</span>`;
        }
        return full(values.join(', '));
    }

    _orNone(value) {
        return value == null ? html`<span class="none">none</span>` : full(value);
    }
}
customElements.define('qwc-quarkpress-modules', QwcQuarkpressModules);
