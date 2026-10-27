import { LitElement, html, css } from 'lit';
import { JsonRpc } from 'jsonrpc';
import { columnBodyRenderer } from '@vaadin/grid/lit.js';
import '@vaadin/grid';
import '@vaadin/button';

function errorText(e) {
    if (e != null && e.error != null && e.error.message != null) {
        return e.error.message;
    }
    return String(e);
}

/** The next scheduled posts of every site and this node's scheduling status since it started. */
export class QwcQuarkpressScheduler extends LitElement {

    jsonRpc = new JsonRpc(this);

    static styles = css`
        :host { display: flex; flex-direction: column; gap: 10px; padding: 0 10px 10px 10px; }
        .error { color: var(--lumo-error-text-color); }
        .none { color: var(--lumo-contrast-50pct); }
        dl { display: grid; grid-template-columns: max-content auto; gap: 4px 16px; margin: 0; }
        dt { font-weight: bold; }
        dd { margin: 0; }
    `;

    static properties = { _view: { state: true }, _error: { state: true } };

    connectedCallback() {
        super.connectedCallback();
        this._refresh();
    }

    _refresh() {
        this._error = null;
        this.jsonRpc.scheduler()
            .then(r => { this._view = r.result; })
            .catch(e => { this._view = null; this._error = errorText(e); });
    }

    render() {
        const v = this._view;
        return html`
            <div><vaadin-button theme="small" @click="${() => this._refresh()}">Refresh</vaadin-button></div>
            ${this._error == null ? '' : html`<div class="error">${this._error}</div>`}
            ${v == null ? '' : html`
                <h3>Next scheduled posts</h3>
                <vaadin-grid .items="${v.upcoming == null ? [] : v.upcoming}" all-rows-visible theme="no-border">
                    <vaadin-grid-column header="Site" auto-width ${columnBodyRenderer(u => html`${u.site}`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Post" auto-width ${columnBodyRenderer(u => html`${u.post}`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Type" auto-width ${columnBodyRenderer(u => html`${u.type}`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Title" resizable ${columnBodyRenderer(u => html`${u.title}`, [])}></vaadin-grid-column>
                    <vaadin-grid-column header="Publish at" auto-width ${columnBodyRenderer(u => html`${u.publishAt}`, [])}></vaadin-grid-column>
                </vaadin-grid>
                <h3>This node</h3>
                <dl>
                    <dt>Last poll started</dt><dd>${this._orNone(v.lastPollStarted)}</dd>
                    <dt>Last poll ended</dt><dd>${this._orNone(v.lastPollEnded)}</dd>
                    <dt>Last batch</dt><dd>${this._batch(v.lastBatch)}</dd>
                    <dt>Published since start</dt><dd>${this._orNone(v.publishedSinceStart)}</dd>
                    <dt>Last failure</dt><dd>${this._failure(v.lastFailure)}</dd>
                    <dt>Last housekeeping</dt><dd>${this._orNone(v.lastHousekeeping)}</dd>
                </dl>`}`;
    }

    _orNone(value) {
        return value == null ? html`<span class="none">none</span>` : html`${value}`;
    }

    _batch(batch) {
        if (batch == null || batch.length === 0) {
            return html`<span class="none">none</span>`;
        }
        return html`${batch.map(p => `site ${p.site} post ${p.post}`).join(', ')}`;
    }

    _failure(failure) {
        if (failure == null) {
            return html`<span class="none">none</span>`;
        }
        const where = [];
        if (failure.site != null) where.push(`site ${failure.site}`);
        if (failure.post != null) where.push(`post ${failure.post}`);
        return html`${failure.at}${where.length === 0 ? '' : ` (${where.join(', ')})`}: ${failure.message}`;
    }
}
customElements.define('qwc-quarkpress-scheduler', QwcQuarkpressScheduler);
