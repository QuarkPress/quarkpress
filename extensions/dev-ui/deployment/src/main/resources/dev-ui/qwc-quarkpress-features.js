import { LitElement, html, css } from 'lit';
import { live } from 'lit/directives/live.js';
import { JsonRpc } from 'jsonrpc';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import '@vaadin/grid';
import '@vaadin/checkbox';

const SITE_KEY = 'quarkpress-dev-ui.site';

/** The site the pages show, kept in local storage; site 1 when none is kept or storage is unavailable. */
function currentSite() {
    try {
        const value = Number(localStorage.getItem(SITE_KEY));
        return Number.isInteger(value) && value > 0 ? value : 1;
    } catch (e) {
        return 1;
    }
}

function errorText(e) {
    if (e != null && e.error != null && e.error.message != null) {
        return e.error.message;
    }
    return String(e);
}

/** The per-site features of the current site: their effective values, where each comes from, and a switch each. */
export class QwcQuarkpressFeatures extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; height: 100%; padding: 0 10px; }
        vaadin-grid { flex: 1; }
        .error { color: var(--lumo-error-text-color); }
    `;

    static properties = {
        _site: { state: true },
        _features: { state: true },
        _error: { state: true },
        _message: { state: true },
        _busy: { state: true }
    };

    constructor() {
        super();
        this._features = [];
        this._error = null;
        this._message = null;
        this._busy = false;
    }

    connectedCallback() {
        super.connectedCallback();
        this._site = currentSite();
        this._load();
    }

    _load() {
        this.jsonRpc.features({ siteId: this._site })
            .then(r => { this._features = r.result == null ? [] : r.result; })
            .catch(e => { this._features = []; this._error = errorText(e); });
    }

    render() {
        return html`
            <div>Site ${this._site}</div>
            <vaadin-grid .items="${this._features}" theme="no-border row-stripes">
                <vaadin-grid-column header="Key" auto-width
                    ${columnBodyRenderer(f => html`${f.key}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Module" auto-width
                    ${columnBodyRenderer(f => html`${f.module}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Description" flex-grow="1" resizable
                    ${columnBodyRenderer(f => html`${f.description}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Enabled" auto-width
                    ${columnBodyRenderer(f => html`<vaadin-checkbox .checked="${live(f.enabled)}" ?disabled="${this._busy}"
                        @change="${e => this._set(f.key, e.target.checked)}"></vaadin-checkbox>`, [this._busy])}></vaadin-grid-column>
                <vaadin-grid-column header="Source" auto-width
                    ${columnBodyRenderer(f => html`${f.source}`, [])}></vaadin-grid-column>
            </vaadin-grid>
            ${this._message == null ? '' : html`<div>${this._message}</div>`}
            ${this._error == null ? '' : html`<div class="error">${this._error}</div>`}`;
    }

    _set(key, enabled) {
        if (this._busy) {
            return;
        }
        this._busy = true;
        this._error = null;
        this._message = null;
        this.jsonRpc.setFeature({ siteId: this._site, key: key, enabled: enabled })
            .then(r => {
                const answer = r.result;
                if (answer == null || answer.error != null) {
                    this._error = answer == null ? 'No answer.' : answer.error;
                    this._load();
                    return;
                }
                const feature = answer.feature;
                this._message = `${feature.key} is now ${feature.enabled ? 'on' : 'off'} (source: ${feature.source})`;
                this._features = this._features.map(f => f.key === feature.key ? feature : f);
            })
            .catch(e => { this._error = errorText(e); this._load(); })
            .finally(() => { this._busy = false; });
    }
}
customElements.define('qwc-quarkpress-features', QwcQuarkpressFeatures);
