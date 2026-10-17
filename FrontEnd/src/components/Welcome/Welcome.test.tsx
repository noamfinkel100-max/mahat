import { render, screen } from '@test-utils';
import { MemoryRouter } from 'react-router-dom';
import { Welcome } from './Welcome';

describe('Welcome component', () => {
  it('introduces the private file vault and provides a signup action', () => {
    render(
      <MemoryRouter>
        <Welcome />
      </MemoryRouter>
    );

    expect(
      screen.getByRole('heading', { name: 'Your files. Protected. Shared.' })
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /create your vault/i })).toHaveAttribute(
      'href',
      '/signup'
    );
  });
});
