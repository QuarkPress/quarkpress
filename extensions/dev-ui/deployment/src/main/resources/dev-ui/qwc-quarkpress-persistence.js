import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import '@vaadin/grid';

function errorText(e) {
    if (e != null && e.error != null && e.error.message != null) {
        return e.error.message;
    }
    return String(e);
}

/** QuarkPress's persistence unit, its datasource and the migrations applied to it. */
export class QwcQuarkpressPersistence extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; height: 100%; padding: 0 10px; }
        vaadin-grid { flex: 1; }
        .error { color: var(--lumo-error-text-color); }
        .none { color: var(--lumo-contrast-50pct); }
        dl { display: grid; grid-template-columns: max-content auto; gap: 4px 16px; margin: 0; }
        dt { font-weight: bold; }
        dd { margin: 0; }
    `;

    static properties = { _view: { state: true }, _error: { state: true } };

    connectedCallback() {
        super.connectedCallback();
        this._error = null;
        this.jsonRpc.persistence()
            .then(r => { this._view = r.result; })
            .catch(e => { this._error = errorText(e); });
    }

    render() {
        if (this._error != null) {
            return html`<div class="error">${this._error}</div>`;
        }
        const v = this._view;
        if (v == null) {
            return html``;
        }
        return html`
            <dl>
                <dt>Persistence unit</dt><dd>${v.persistenceUnit}</dd>
                <dt>Datasource</dt><dd>${v.datasource}</dd>
                <dt>Database kind</dt><dd>${v.dbKind}</dd>
                <dt>History table</dt><dd>${v.historyTable}</dd>
            </dl>
            <vaadin-grid .items="${v.migrations == null ? [] : v.migrations}" theme="no-border row-stripes">
                <vaadin-grid-column header="Version" auto-width ${columnBodyRenderer(m => this._orNone(m.version), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Description" resizable ${columnBodyRenderer(m => html`${m.description}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Script" auto-width resizable ${columnBodyRenderer(m => html`${m.script}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Installed on" auto-width ${columnBodyRenderer(m => this._orNone(m.installedOn), [])}></vaadin-grid-column>
                <vaadin-grid-column header="Success" auto-width ${columnBodyRenderer(m => html`${m.success ? 'yes' : 'no'}`, [])}></vaadin-grid-column>
            </vaadin-grid>`;
    }

    _orNone(value) {
        return value == null ? html`<span class="none">none</span>` : html`${value}`;
    }
}
customElements.define('qwc-quarkpress-persistence', QwcQuarkpressPersistence);
